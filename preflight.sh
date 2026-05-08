#!/usr/bin/env bash
# Vaier pre-flight check. Run from the directory that holds docker-compose.yml.
# Validates the host, the downloaded files, the .env contents, and DNS state.
set -u

OK=$'\033[32m\xE2\x9C\x93\033[0m'
FAIL=$'\033[31m\xE2\x9C\x97\033[0m'
WARN=$'\033[33m\xE2\x9A\xA0\033[0m'
INFO=$'\033[34mi\033[0m'

errors=0
warnings=0
pass()    { printf '  %s %s\n' "$OK"   "$1"; }
fail()    { printf '  %s %s\n' "$FAIL" "$1"; errors=$((errors+1)); }
warn()    { printf '  %s %s\n' "$WARN" "$1"; warnings=$((warnings+1)); }
info()    { printf '  %s %s\n' "$INFO" "$1"; }
section() { printf '\n── %s ──\n' "$1"; }

REPO_RAW="https://raw.githubusercontent.com/getvaier/vaier/main"

section "Docker"
if command -v docker >/dev/null; then
  pass "docker installed: $(docker --version)"
else
  fail "docker not installed"
fi
if docker compose version >/dev/null 2>&1; then
  CV=$(docker compose version --short 2>/dev/null || echo "0.0.0")
  CV_MAJOR=${CV%%.*}
  CV_REST=${CV#*.}
  CV_MINOR=${CV_REST%%.*}
  if (( CV_MAJOR > 2 )) || (( CV_MAJOR == 2 && CV_MINOR >= 23 )); then
    pass "docker compose plugin v$CV (≥ 2.23 required for inline configs)"
  else
    fail "docker compose plugin v$CV is too old; v2.23+ required for the inline configs: block. Upgrade: curl -fsSL https://get.docker.com | sh"
  fi
else
  fail "docker compose plugin missing"
fi

section "Compose file"
if [[ -f docker-compose.yml ]]; then
  pass "docker-compose.yml present"
else
  fail "docker-compose.yml missing — curl -fsSL $REPO_RAW/docker-compose.yml -o docker-compose.yml"
fi

section ".env"
AWS_MODE=0
if [[ -f .env ]]; then
  pass ".env present"
  # shellcheck disable=SC1091
  set -a; . ./.env; set +a
  [[ -n "${VAIER_DOMAIN:-}" ]] && pass "VAIER_DOMAIN=$VAIER_DOMAIN" || fail "VAIER_DOMAIN is not set"
  [[ -n "${ACME_EMAIL:-}"  ]] && pass "ACME_EMAIL=$ACME_EMAIL"   || fail "ACME_EMAIL is not set"
  if [[ -n "${VAIER_AWS_KEY:-}" && -n "${VAIER_AWS_SECRET:-}" ]]; then
    info "AWS creds present → Route53 mode (Vaier auto-creates vaier.\$VAIER_DOMAIN and login.\$VAIER_DOMAIN)"
    AWS_MODE=1
  else
    info "AWS creds absent → manual DNS mode (you must create vaier.\$VAIER_DOMAIN and login.\$VAIER_DOMAIN yourself BEFORE first boot)"
  fi
  PERM=$(stat -c '%a' .env 2>/dev/null || stat -f '%A' .env 2>/dev/null || echo "?")
  [[ "$PERM" == "600" ]] && pass ".env perms 600" || warn ".env perms $PERM (README suggests 600)"
else
  fail ".env missing — see Quick Start step 3"
fi

section "Kernel / WireGuard"
info "kernel $(uname -r)"
if grep -q '^wireguard ' /proc/modules 2>/dev/null; then
  pass "wireguard module already loaded"
elif modprobe -n wireguard >/dev/null 2>&1; then
  pass "wireguard module loadable"
else
  warn "wireguard module not present; modern kernels have it built-in and the lscr.io/wireguard image will load it on first start. If it fails after 'docker compose up', install kernel headers/wireguard-tools."
fi

section "EC2 IMDSv2"
PUB_IP=""
TOKEN=$(curl -fs --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" \
  -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)
if [[ -n "$TOKEN" ]]; then
  PUB_IP=$(curl -fs --max-time 2 -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)
  PUB_HOST=$(curl -fs --max-time 2 -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/public-hostname 2>/dev/null || true)
  pass "IMDSv2 reachable from host (public-ipv4=$PUB_IP)"
  # Probe IMDS from inside the default Docker bridge to detect hop-limit=1.
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    DOCKER_PROBE=$(docker run --rm --network bridge curlimages/curl:8.10.1 \
      -fs --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" \
      -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)
    if [[ -n "$DOCKER_PROBE" ]]; then
      pass "IMDSv2 reachable from Docker bridge (hop-limit OK)"
    else
      warn "IMDSv2 NOT reachable from Docker bridge — instance metadata hop-limit is 1. Either raise it (aws ec2 modify-instance-metadata-options --http-put-response-hop-limit 2) or set VAIER_PUBLIC_IP=$PUB_IP in .env"
    fi
  fi
else
  warn "IMDSv2 not reachable; Vaier cannot auto-detect public IP. Set VAIER_PUBLIC_IP in .env."
fi

section "Host port conflicts"
for p in 80 443; do
  if ss -ltnH "sport = :$p" 2>/dev/null | grep -q .; then
    fail "TCP $p already bound by another process"
  else
    pass "TCP $p free"
  fi
done
if ss -lunH "sport = :51820" 2>/dev/null | grep -q .; then
  fail "UDP 51820 already bound by another process"
else
  pass "UDP 51820 free"
fi

section "Public DNS"
if ! command -v dig >/dev/null; then
  info "dig not installed; skipping DNS checks (apt install dnsutils  OR  dnf install bind-utils)"
elif [[ -n "${VAIER_DOMAIN:-}" ]]; then
  NS=$(dig +short NS "${VAIER_DOMAIN}" @1.1.1.1 2>/dev/null | tr -d '\r')
  if [[ -z "$NS" ]]; then
    fail "${VAIER_DOMAIN} has no public NS records — registrar delegation not in place"
  elif (( AWS_MODE == 1 )) && echo "$NS" | grep -qi 'awsdns'; then
    pass "${VAIER_DOMAIN} delegated to Route53 (NS: $(echo "$NS" | tr '\n' ' '))"
  elif (( AWS_MODE == 1 )); then
    fail "${VAIER_DOMAIN} NS not awsdns.* — Route53 hosted zone may exist but registrar isn't delegated. HTTP-01 will fail. NS: $(echo "$NS" | tr '\n' ' ')"
  else
    pass "${VAIER_DOMAIN} has NS records: $(echo "$NS" | tr '\n' ' ')"
  fi
  for sub in vaier login; do
    REC=$(dig +short "$sub.${VAIER_DOMAIN}" @1.1.1.1 2>/dev/null | tr -d '\r')
    if [[ -n "$REC" ]]; then
      pass "$sub.${VAIER_DOMAIN} resolves → $(echo "$REC" | tr '\n' ' ')"
    elif (( AWS_MODE == 1 )); then
      info "$sub.${VAIER_DOMAIN} not yet resolving (Vaier will create it on first boot if the hosted zone exists)"
    else
      fail "$sub.${VAIER_DOMAIN} doesn't resolve — manual DNS mode requires you to create this BEFORE 'docker compose up'"
    fi
  done
fi

section "Public ingress sanity (best effort)"
if [[ -n "$PUB_IP" ]]; then
  EXT=$(curl -fs --max-time 5 https://icanhazip.com 2>/dev/null | tr -d '\n')
  if [[ -n "$EXT" && "$EXT" == "$PUB_IP" ]]; then
    pass "outbound IP matches IMDS public IP ($EXT)"
  elif [[ -n "$EXT" ]]; then
    warn "outbound IP ($EXT) ≠ IMDS public IP ($PUB_IP); behind NAT or instance has multiple addresses"
  fi
  info "Inbound reachability must be tested from OUTSIDE. From your laptop:"
  info "  nc -vz $PUB_IP 80 && nc -vz $PUB_IP 443"
  info "  nmap -sU -p 51820 $PUB_IP   # UDP needs nmap; nc -u won't tell you anything useful"
fi

section "Summary"
if (( errors == 0 && warnings == 0 )); then
  printf '%s All checks passed. Run: docker compose up -d\n' "$OK"
elif (( errors == 0 )); then
  printf '%s %d warning(s); review above before first boot.\n' "$WARN" "$warnings"
else
  printf '%s %d error(s), %d warning(s). Fix the errors before docker compose up -d.\n' "$FAIL" "$errors" "$warnings"
  exit 1
fi
