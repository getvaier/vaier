#!/usr/bin/env bash
#
# Vaier one-shot installer — rigs a machine to run the stack with NO git clone.
#
# The compose stack bind-mounts a handful of committed asset files (the nginx offline page,
# oauth2-proxy templates, the Dex sign-in theme). A plain `curl docker-compose.yml` leaves those
# paths missing, dockerd then auto-creates them as empty directories, and the first single-file
# mount fails at container start. This script fetches exactly those runtime files (and the compose
# file) from the release tarball — history-free, so it's runtime stuff only — and scaffolds a .env.
#
# Usage:
#   mkdir -p vaier && cd vaier
#   curl -fsSL https://raw.githubusercontent.com/getvaier/vaier/main/install.sh | bash
#
# Safe to re-run on an existing install, and that is also how you UPGRADE the stack: it refreshes the
# compose file and the committed assets, leaves .env alone, and tops up any auto-generated secret the
# .env predates. That last part is not a nicety — a release that adds a secret finds every existing
# .env without it, and compose now refuses to start rather than interpolate an empty one (see the
# ${VAIER_..._SECRET:?} guards in docker-compose.yml). Re-running here is what clears that.
#
# Run at a terminal it also asks for your domain, email and time zone, offers to install Docker and to
# start the stack, and prints your first sign-in. Without a terminal — piped to a log, CI, or Vaier's own
# self-update, which sets VAIER_NONINTERACTIVE=1 — it only fetches and scaffolds, and asks nothing.
#
# Override the ref (branch, tag or commit) with VAIER_REF, e.g. VAIER_REF=v1.2.3.
set -euo pipefail

REPO="${VAIER_REPO:-getvaier/vaier}"
REF="${VAIER_REF:-main}"

# The ONLY runtime files the stack needs pre-placed before `docker compose up`: the compose file
# plus every committed asset tree it bind-mounts. Everything else (wireguard/config, traefik/config,
# vaier/config, geoip, dex/config, oauth2/config, icons, acme) is created at runtime by an init
# container or named volume, so it must NOT be fetched here. Keep this list in sync with the compose
# file's bind mounts — InstallScriptCoverageTest fails the build if it drifts. Vaier's self-update runs
# this script at the commit its new image was built from, after backing up these same paths
# (SelfUpdateScript.RUNTIME_PATHS, held equal to this list by the same test).
RUNTIME_PATHS=(
  docker-compose.yml
  offline
  oauth2/templates
  dex/themes
  # #329: the one committed, non-secret CrowdSec acquisition file — tells the Security Engine
  # which log file to tail. Everything else under crowdsec/ is runtime-generated.
  crowdsec/acquis.d
)

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m warning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m error:\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required."
command -v tar  >/dev/null 2>&1 || die "tar is required."

# A person at a terminal. Under `curl | bash` stdin is the script itself, so answers come from /dev/tty.
interactive=false
if [ -z "${VAIER_NONINTERACTIVE:-}" ] && [ -t 1 ] && { : </dev/tty; } 2>/dev/null; then interactive=true; fi

if ! command -v docker >/dev/null 2>&1; then
  $interactive || warn "docker not found. Install it first:  curl -fsSL https://get.docker.com | sh"
elif ! docker compose version >/dev/null 2>&1; then
  warn "docker compose v2 not found. Vaier needs Compose v2.23+ (bundled with current Docker)."
fi

# A premature `docker compose up` (before these files existed) makes dockerd create the bind-mount
# source dirs as root, so a later run as an unprivileged user can't write into them. Catch that here
# with a precise fix, rather than letting tar fail with a misleading "check your network".
blocked=()
for d in . offline oauth2 dex; do
  if [ -e "$d" ] && [ ! -w "$d" ]; then blocked+=("$d"); fi
done
if [ "${#blocked[@]}" -gt 0 ]; then
  die "these paths aren't writable — most likely root-owned leftovers from an earlier 'docker compose up':
     ${blocked[*]}
   Clean them and retry (keeps your .env):
     docker compose down 2>/dev/null; sudo rm -rf ${blocked[*]}
   then re-run this installer."
fi

say "Fetching Vaier runtime files (${REPO}@${REF}) — no git history."
# Extract only the runtime members from the tarball. The archive's top dir is vaier-<ref>; the
# leading */ glob absorbs it (tar's wildcards match '/'), and --strip-components=1 removes it so the
# files land in the current directory. Naming a directory member pulls its whole subtree.
tar_members=()
for p in "${RUNTIME_PATHS[@]}"; do
  tar_members+=( "*/${p}" )
done

curl -fsSL "https://codeload.github.com/${REPO}/tar.gz/${REF}" \
  | tar -xz --strip-components=1 --wildcards "${tar_members[@]}" \
  || die "Failed to fetch runtime files — check the ref (VAIER_REF='${REF}'), your network, and that no
   target dir is root-owned from an earlier 'docker compose up' (see the writability check above)."

# Sanity-check the single-file mount that fails loudest when missing.
[ -f offline/default.conf ] || die "offline/default.conf did not download — aborting before a broken 'up'."

say "Runtime files in place:"
printf '   %s\n' "${RUNTIME_PATHS[@]}"

if [ -f .env ]; then
  say ".env already exists — leaving it untouched."
else
  say "Scaffolding .env template."
  cat > .env <<'EOF'
# --- Vaier configuration — fill these in, then run: docker compose up -d ---

# Your base domain, and the Let's Encrypt contact email.
VAIER_DOMAIN=yourdomain.com
ACME_EMAIL=you@yourdomain.com

# Social sign-in — optional, and it can wait. With none configured the stack opens a first-run
# door: 'docker compose logs vaier' prints a one-account password, and that first sign-in becomes
# the admin. Register redirect URI https://dex.<VAIER_DOMAIN>/callback for each provider you add.
#   Google — https://console.cloud.google.com/apis/credentials
#   GitHub — https://github.com/settings/developers
VAIER_OIDC_GOOGLE_CLIENT_ID=
VAIER_OIDC_GOOGLE_CLIENT_SECRET=
VAIER_OIDC_GITHUB_CLIENT_ID=
VAIER_OIDC_GITHUB_CLIENT_SECRET=

# The email that becomes the first admin (optional: the first-run door uses ACME_EMAIL if blank).
VAIER_ADMIN_EMAIL=

# The zone Vaier reads local time in (the nightly backup hour is this zone, not UTC). Defaults to UTC.
VAIER_TZ=UTC

# DNS is one record, made once at your DNS host before the first 'up' — any provider will do:
#   *.yourdomain.com   A   <this server's public IP>
# Vaier never writes DNS; every service it publishes resolves through that one record.
EOF
  chmod 600 .env
fi

# The Dex<->oauth2-proxy shared secret and the oauth2-proxy cookie secret are NOT operator-authored,
# and nothing in the compose stack generates them: a .env missing VAIER_DEX_CLIENT_SECRET renders an
# empty Dex static-client secret and Dex crash-loops ("Secret ... is required for client vaier-oauth2").
# Generate any that are absent, in place — so both a fresh scaffold and a pre-existing .env end up
# complete. Only ever appends a missing key; never touches a value the operator already set.
gen_hex() { openssl rand -hex 32 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
gen_b64() { openssl rand -base64 32 2>/dev/null || head -c 32 /dev/urandom | base64 | tr -d '\n'; }
ensure_secret() {   # $1=var name  $2=generator function
  grep -qE "^$1=.+" .env 2>/dev/null && return 0
  printf '%s=%s\n' "$1" "$("$2")" >> .env
  say "Generated $1"
}
ensure_secret VAIER_DEX_CLIENT_SECRET gen_hex
ensure_secret VAIER_OAUTH2_COOKIE_SECRET gen_b64
# #329: the shared bouncer API key between crowdsec (BOUNCER_KEY_vaier, self-registers on boot)
# and Traefik's bouncer plugin (CROWDSEC_BOUNCER_API_KEY). Not operator-authored — same reasoning as the
# two secrets above.
ensure_secret VAIER_CROWDSEC_BOUNCER_KEY gen_hex

# --- The interactive finish (#304) ---------------------------------------------------------------------
ask() {       # $1=question $2=default — prints the answer
  local reply=''
  read -r -p "$1${2:+ [$2]}: " reply </dev/tty || true
  printf '%s' "${reply:-${2:-}}"
}
confirm() {   # $1=question — yes unless the answer starts with n
  local reply=''
  read -r -p "$1 [Y/n] " reply </dev/tty || true
  case "$reply" in [nN]*) return 1 ;; *) return 0 ;; esac
}
env_value() { sed -n "s/^$1=//p" .env | tail -1; }
set_env() {   # $1=key $2=value — replaces the key's line, or appends it
  local tmp; tmp=$(mktemp)
  awk -v k="$1" -v v="$2" '$0 ~ "^" k "=" { print k "=" v; done = 1; next } { print } END { if (!done) print k "=" v }' .env > "$tmp"
  cat "$tmp" > .env && rm -f "$tmp"   # cat, not mv: .env keeps its 600 mode
}

if $interactive; then
  sudo=''; [ "$(id -u)" -eq 0 ] || sudo='sudo'
  domain=$(env_value VAIER_DOMAIN)
  fresh=false
  if [ -z "$domain" ] || [ "$domain" = yourdomain.com ]; then
    fresh=true
    printf '\n'; say "Three questions, then Vaier can start. Everything else is set up later in its console."
    while :; do
      domain=$(ask "Your domain — Vaier will live at vaier.<domain>, e.g. example.com" "")
      [[ "$domain" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]] && break
      warn "That doesn't look like a domain name."
    done
    while :; do
      email=$(ask "Your email — for Let's Encrypt, and your first sign-in" "")
      [[ "$email" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] && break
      warn "That doesn't look like an email address."
    done
    tz_here=$(timedatectl show -p Timezone --value 2>/dev/null || cat /etc/timezone 2>/dev/null || true)
    while :; do
      tz=$(ask "Time zone for schedules such as the nightly backup" "${tz_here:-UTC}")
      [ "$tz" = UTC ] || [ -f "/usr/share/zoneinfo/$tz" ] && break
      warn "No time zone called '$tz' (try a name like Europe/Oslo)."
    done
    set_env VAIER_DOMAIN "$domain"; set_env ACME_EMAIL "$email"; set_env VAIER_TZ "$tz"
    say "Saved to .env."

    # Vaier makes no DNS; the one wildcard record is the operator's, so say exactly which one.
    ip=$(curl -fsS -m 5 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)
    seen=$(getent ahostsv4 "vaier.$domain" 2>/dev/null | awk 'NR == 1 { print $1 }' || true)
    if [ -n "$ip" ] && [ "$seen" = "$ip" ]; then
      say "DNS: vaier.$domain already points at this server ($ip)."
    else
      warn "vaier.$domain doesn't point at this server yet${seen:+ (it points at $seen)}. Make this one record at your DNS host:
     *.$domain   A   ${ip:-<the public IP of this server>}
   You can start now: Vaier waits for it before asking Let's Encrypt for certificates."
    fi
  fi

  if ! command -v docker >/dev/null 2>&1 && confirm "Docker isn't installed. Install it now with Docker's official script?"; then
    curl -fsSL https://get.docker.com | $sudo sh || die "Docker did not install — see the output above."
  fi
  if command -v docker >/dev/null 2>&1; then
    docker_cmd=(docker); docker info >/dev/null 2>&1 || docker_cmd=($sudo docker)
    if confirm "$($fresh && echo "Start Vaier now?" || echo "Bring Vaier up to date now?")"; then
      "${docker_cmd[@]}" compose pull --quiet && "${docker_cmd[@]}" compose up -d \
        || die "The stack didn't start — see the output above."
      if ! $fresh; then say "Vaier is up to date."; exit 0; fi
      say "Waiting for Vaier to start (a minute or two the first time)…"
      first_sign_in='' started=0
      for _ in $(seq 1 120); do
        logs=$("${docker_cmd[@]}" compose logs --no-log-prefix vaier 2>/dev/null || true)
        first_sign_in=$(printf '%s\n' "$logs" | grep -E '^  (Open|Sign in|Email|Password) ' | tail -4 || true)
        [ -n "$first_sign_in" ] && break
        printf '%s' "$logs" | grep -q 'Started VaierApplication' && started=$((started + 1))
        [ "$started" -gt 5 ] && break   # up, but no first-run door: a sign-in provider is already set
        sleep 2
      done
      if [ -n "$first_sign_in" ]; then
        printf '\n%s\n\n%s\n\n%s\n' "$(say "Vaier is up. Your first sign-in:")" "$first_sign_in" \
          "  That first sign-in becomes the admin. Add Google or GitHub under People, Sign-in providers, to invite anyone else."
      elif [ "$started" -gt 0 ]; then
        say "Vaier is up: https://vaier.$domain"
      else
        warn "Vaier hasn't finished starting yet. Watch it with:  docker compose logs -f vaier"
      fi
      exit 0
    fi
  fi
fi

domain=$(env_value VAIER_DOMAIN)
if [ -n "$domain" ] && [ "$domain" != yourdomain.com ]; then
  # The interactive run already worked out the address and whether DNS points here.
  record_ip=${ip:-"<this server's public IP>"}
  dns_step="  - Point DNS       — one record, once:  *.$domain  A  $record_ip
"
  [ -n "${ip:-}" ] && [ "${seen:-}" = "$ip" ] && dns_step=''
  cat <<EOF

$(say "Done.")
Next:
${dns_step}  - Start the stack — docker compose up -d
  - Sign in         — docker compose logs vaier   prints the first-run password at the bottom;
                       that first sign-in becomes the admin.
EOF
else
  cat <<EOF

$(say "Done.")
Next:
  1. Edit .env       — set your domain and Let's Encrypt email; nothing else is required.
                       (every auto-generated secret is already filled in for you.)
  2. Point DNS       — one record, once:  *.<domain>  A  <this server's public IP>
  3. Start the stack — docker compose up -d
  4. Sign in         — docker compose logs vaier   prints the first-run password at the bottom;
                       that first sign-in becomes the admin. Add Google or GitHub whenever you
                       want to invite anyone else.

Upgrading an existing install? Steps 1 and 2 are already done — just: docker compose up -d
EOF
fi
