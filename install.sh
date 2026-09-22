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
# Override the ref (branch or tag) with VAIER_REF, e.g. VAIER_REF=v1.2.3.
set -euo pipefail

REPO="${VAIER_REPO:-getvaier/vaier}"
REF="${VAIER_REF:-main}"

# The ONLY runtime files the stack needs pre-placed before `docker compose up`: the compose file
# plus every committed asset tree it bind-mounts. Everything else (wireguard/config, traefik/config,
# vaier/config, geoip, dex/config, oauth2/config, icons, acme) is created at runtime by an init
# container or named volume, so it must NOT be fetched here. Keep this list in sync with the compose
# file's bind mounts — InstallScriptCoverageTest fails the build if it drifts.
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

if ! command -v docker >/dev/null 2>&1; then
  warn "docker not found. Install it first:  curl -fsSL https://get.docker.com | sh"
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

curl -fsSL "https://codeload.github.com/${REPO}/tar.gz/refs/heads/${REF}" \
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

# Social sign-in. Register redirect URI https://dex.<VAIER_DOMAIN>/callback for each provider.
#   Google — https://console.cloud.google.com/apis/credentials
#   GitHub — https://github.com/settings/developers
VAIER_OIDC_GOOGLE_CLIENT_ID=
VAIER_OIDC_GOOGLE_CLIENT_SECRET=
VAIER_OIDC_GITHUB_CLIENT_ID=
VAIER_OIDC_GITHUB_CLIENT_SECRET=

# The email that becomes the first admin.
VAIER_ADMIN_EMAIL=you@gmail.com

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

cat <<EOF

$(say "Done.")
Next:
  1. Edit .env       — set your domain, admin email, and OAuth client ids/secrets.
                       (every auto-generated secret is already filled in for you.)
  2. Point DNS       — one record, once:  *.<domain>  A  <this server's public IP>
  3. Start the stack — docker compose up -d

Upgrading an existing install? Steps 1 and 2 are already done — just: docker compose up -d
EOF
