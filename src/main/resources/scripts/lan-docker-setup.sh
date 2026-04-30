#!/usr/bin/env bash
#
# Vaier LAN docker host setup
#
# Exposes the Docker engine API on tcp://0.0.0.0:<port> so that a Vaier instance
# (running on a relay peer's LAN) can scrape containers remotely. Idempotent —
# safe to re-run. Covers both native Docker (systemd) and Snap Docker.
#
# Usage (run on the LAN docker host):
#   curl -sSL https://vaier.<your-domain>/lan-servers/docker-setup.sh \
#     | sudo bash -s -- --port 2375
#
# WARNING: tcp://0.0.0.0:2375 is unencrypted and unauthenticated. Only use this
# on a LAN you trust. Vaier V1 does not yet support TLS / SSH for the LAN docker
# socket — that's tracked separately.

set -euo pipefail

PORT=2375

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --port=*) PORT="${1#--port=}"; shift ;;
        -h|--help)
            sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "ERROR: --port must be an integer between 1 and 65535 (got '$PORT')" >&2
    exit 2
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: this script must be run as root (try: sudo bash $0 --port $PORT)" >&2
    exit 2
fi

echo "==> Vaier LAN docker setup — exposing Docker API on tcp://0.0.0.0:${PORT}"

DESIRED_HOSTS="\"unix:///var/run/docker.sock\", \"tcp://0.0.0.0:${PORT}\""

write_daemon_json() {
    local target="$1"
    local target_dir
    target_dir="$(dirname "$target")"
    mkdir -p "$target_dir"

    if [ -f "$target" ] && grep -Fq "tcp://0.0.0.0:${PORT}" "$target" \
            && grep -Fq "unix:///var/run/docker.sock" "$target"; then
        echo "    daemon.json at $target already configured for tcp://0.0.0.0:${PORT} — skipping"
        return 1
    fi

    if [ -f "$target" ] && [ ! -f "${target}.vaier.bak" ]; then
        cp "$target" "${target}.vaier.bak"
        echo "    backed up existing daemon.json to ${target}.vaier.bak"
    fi

    cat > "$target" <<DAEMON_JSON
{
    "hosts": [${DESIRED_HOSTS}]
}
DAEMON_JSON
    echo "    wrote $target"
    return 0
}

wait_for_docker() {
    local waited=0
    until docker info > /dev/null 2>&1; do
        if [ $waited -ge 30 ]; then
            echo "ERROR: Docker did not come up within 30s" >&2
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
}

if command -v snap >/dev/null 2>&1 && snap list docker >/dev/null 2>&1; then
    echo "==> Detected Snap Docker"
    SNAP_DAEMON_JSON="/var/snap/docker/current/config/daemon.json"

    if write_daemon_json "$SNAP_DAEMON_JSON"; then
        echo "==> Restarting snap.docker.dockerd"
        systemctl restart snap.docker.dockerd
    fi

    wait_for_docker
else
    echo "==> Detected native Docker"
    NATIVE_DAEMON_JSON="/etc/docker/daemon.json"
    DROPIN_DIR="/etc/systemd/system/docker.service.d"
    DROPIN="${DROPIN_DIR}/vaier-remote-api.conf"

    daemon_changed=0
    if write_daemon_json "$NATIVE_DAEMON_JSON"; then
        daemon_changed=1
    fi

    # systemd's docker.service typically passes "-H fd://", which conflicts with
    # the "hosts" key in daemon.json. Drop a unit override that clears ExecStart
    # and re-runs dockerd with no -H, letting daemon.json drive the hosts list.
    mkdir -p "$DROPIN_DIR"
    DROPIN_BODY="[Service]
ExecStart=
ExecStart=/usr/bin/dockerd
"
    dropin_changed=0
    if [ ! -f "$DROPIN" ] || [ "$(cat "$DROPIN")" != "$DROPIN_BODY" ]; then
        printf '%s' "$DROPIN_BODY" > "$DROPIN"
        echo "    wrote $DROPIN"
        dropin_changed=1
    else
        echo "    systemd drop-in at $DROPIN already in place — skipping"
    fi

    if [ $dropin_changed -eq 1 ]; then
        systemctl daemon-reload
    fi

    if [ $daemon_changed -eq 1 ] || [ $dropin_changed -eq 1 ]; then
        echo "==> Restarting docker"
        systemctl restart docker
    else
        echo "==> No changes — Docker not restarted"
    fi

    wait_for_docker
fi

echo
echo "==> Verifying remote API on tcp://127.0.0.1:${PORT}"
if command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/_ping" >/dev/null; then
        echo "    OK — Docker engine API is reachable on port ${PORT}"
    else
        echo "    WARNING: curl http://127.0.0.1:${PORT}/_ping failed — check docker logs" >&2
        exit 1
    fi
else
    echo "    (curl not installed, skipping verification)"
fi

echo
echo "Done. Register this host in Vaier with:"
echo "  - Name:       <hostname>"
echo "  - LAN address: <this host's IP on the relay peer's LAN>"
echo "  - Runs Docker: yes"
echo "  - Docker port: ${PORT}"
