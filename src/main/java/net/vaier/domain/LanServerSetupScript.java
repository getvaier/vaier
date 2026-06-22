package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Generates the single bootstrap shell script a registered LAN server runs to prepare itself for
 * Vaier. It adapts to what the host needs:
 * <ul>
 *   <li>when {@code dockerPort != null} — installs Docker (if absent) and exposes the engine API
 *       on {@code tcp://0.0.0.0:<port>} so Vaier can scrape it (native + Snap installs);</li>
 *   <li>when {@code gateway != null} — installs static routes (server LAN CIDR, VPN subnet, and
 *       sibling relays' LANs) via the host's relay peer, persisted across reboots.</li>
 * </ul>
 * A pure, IO-free generator in the same family as {@link PeerSetupScript} / {@link WireGuardPeerConfig}.
 */
public final class LanServerSetupScript {

    private LanServerSetupScript() {}

    /** Default Docker engine API port when a Docker-enabled LAN server doesn't specify one. */
    public static final int DEFAULT_DOCKER_PORT = 2375;

    /**
     * Decides what a registered LAN server's setup script must do and renders it — the business
     * logic of the feature, kept in the domain. Given the server and the data its callers read from
     * driven ports (all peer configs, the server LAN CIDR, the VPN subnet), it:
     * <ul>
     *   <li>exposes the Docker API when the server {@link LanServer#runsDocker()} (its
     *       {@link LanServer#dockerPort()} or {@link #DEFAULT_DOCKER_PORT});</li>
     *   <li>installs routes via the relay peer when the server is anchored at one (not at the Vaier
     *       server, which is already on its own subnet) — see {@link #routedDestinations}.</li>
     * </ul>
     * Returns empty when there is nothing to set up (no Docker and not relay-anchored). Throws
     * {@link ConflictException} when the relay peer has no LAN address to route via.
     */
    public static Optional<String> forHost(LanServer server, List<PeerConfiguration> allPeers,
                                           String serverLanCidr, String vpnSubnet) {
        Integer dockerPort = server.runsDocker()
            ? (server.dockerPort() != null ? server.dockerPort() : DEFAULT_DOCKER_PORT)
            : null;

        String gateway = null;
        List<String> cidrs = List.of();
        Optional<LanAnchor> anchor = LanAnchor.resolve(server.lanAddress(), allPeers, serverLanCidr);
        if (anchor.isPresent() && !anchor.get().isVaierServer()) {
            PeerConfiguration relay = anchor.get().relayPeer().orElseThrow();
            gateway = relay.lanAddress();
            if (gateway == null || gateway.isBlank()) {
                throw new ConflictException("Relay peer " + relay.name()
                    + " has no LAN address set — set it before generating a setup script for "
                    + server.name());
            }
            cidrs = routedDestinations(relay, allPeers, serverLanCidr, vpnSubnet);
        }

        if (dockerPort == null && gateway == null) return Optional.empty();
        return Optional.of(generate(dockerPort, gateway, cidrs));
    }

    /**
     * The CIDRs a relay-anchored LAN host should route via its relay peer: the {@code serverLanCidr},
     * the {@code vpnSubnet}, and every <em>other</em> relay peer's {@code lanCidr} (so this host can
     * reach the Vaier server's subnet, VPN peers, and other sites' LANs). The host's own relay LAN is
     * excluded — the host is already on it. Ordered and de-duplicated; blanks dropped.
     */
    public static List<String> routedDestinations(PeerConfiguration relay, List<PeerConfiguration> allPeers,
                                                  String serverLanCidr, String vpnSubnet) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (serverLanCidr != null && !serverLanCidr.isBlank()) out.add(serverLanCidr.trim());
        if (vpnSubnet != null && !vpnSubnet.isBlank()) out.add(vpnSubnet.trim());
        String ownLan = (relay.lanCidr() == null) ? null : relay.lanCidr().trim();
        for (PeerConfiguration p : allPeers) {
            String lc = p.lanCidr();
            if (lc == null || lc.isBlank()) continue;
            lc = lc.trim();
            if (lc.equals(ownLan)) continue;
            out.add(lc);
        }
        return new ArrayList<>(out);
    }

    public static String generate(Integer dockerPort, String gateway, List<String> routeCidrs) {
        boolean doDocker = dockerPort != null;
        boolean doRoutes = gateway != null && routeCidrs != null && !routeCidrs.isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("#\n");
        sb.append("# Vaier LAN host setup — idempotent, safe to re-run. This host needs:\n");
        if (doDocker) sb.append("#   - Docker engine API exposed on tcp://0.0.0.0:").append(dockerPort).append("\n");
        if (doRoutes) sb.append("#   - static routes to the Vaier server subnet (and other site LANs) via its relay peer\n");
        sb.append("#\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("if [ \"$(id -u)\" -ne 0 ]; then\n");
        sb.append("    echo \"ERROR: run this script as root (sudo bash $0)\" >&2\n");
        sb.append("    exit 2\n");
        sb.append("fi\n");

        if (doDocker) sb.append(dockerBlock(dockerPort));
        if (doRoutes) sb.append(routeBlock(gateway, routeCidrs));

        sb.append("\necho\n");
        sb.append("echo \"==> Vaier LAN host setup complete.\"\n");
        if (doDocker) {
            sb.append("echo \"    Docker API is on tcp://0.0.0.0:").append(dockerPort)
                .append(" — allow inbound TCP ").append(dockerPort)
                .append(" from the Vaier server only (it is unauthenticated; never expose it to the internet).\"\n");
        }
        return sb.toString();
    }

    /** Docker-engine-API exposure. Port baked into a {@code PORT} shell var; no backslash escaping needed. */
    private static String dockerBlock(int port) {
        return "\nPORT=" + port + "\n" + """
            echo "==> Exposing Docker API on tcp://0.0.0.0:${PORT}"

            # 1. install Docker if it isn't already present
            if command -v docker >/dev/null 2>&1; then
                echo "    Docker already installed: $(docker --version 2>/dev/null || echo present)"
            elif command -v snap >/dev/null 2>&1 && snap list docker >/dev/null 2>&1; then
                echo "    Docker (snap) already installed"
            else
                echo "    Docker not found — installing via https://get.docker.com"
                curl -fsSL https://get.docker.com | sh
                systemctl enable --now docker >/dev/null 2>&1 || true
            fi

            write_daemon_json() {
                local target="$1"
                mkdir -p "$(dirname "$target")"
                if [ -f "$target" ] && grep -Fq "tcp://0.0.0.0:${PORT}" "$target" \\
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
                "hosts": ["unix:///var/run/docker.sock", "tcp://0.0.0.0:${PORT}"]
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
                if write_daemon_json "/var/snap/docker/current/config/daemon.json"; then
                    systemctl restart snap.docker.dockerd
                fi
                wait_for_docker
            else
                echo "==> Detected native Docker"
                DROPIN_DIR="/etc/systemd/system/docker.service.d"
                DROPIN="${DROPIN_DIR}/vaier-remote-api.conf"
                daemon_changed=0
                if write_daemon_json "/etc/docker/daemon.json"; then daemon_changed=1; fi
                # docker.service usually passes "-H fd://", which conflicts with the hosts key in
                # daemon.json. Clear ExecStart and let daemon.json drive the hosts list.
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
                fi
                if [ $dropin_changed -eq 1 ]; then systemctl daemon-reload; fi
                if [ $daemon_changed -eq 1 ] || [ $dropin_changed -eq 1 ]; then
                    systemctl restart docker
                else
                    echo "==> No Docker changes"
                fi
                wait_for_docker
            fi

            echo "==> Verifying Docker API on tcp://127.0.0.1:${PORT}"
            if command -v curl >/dev/null 2>&1; then
                if curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/_ping" >/dev/null; then
                    echo "    OK — Docker engine API reachable on port ${PORT}"
                else
                    echo "    WARNING: /_ping on port ${PORT} failed — check docker logs" >&2
                fi
            fi
            echo "    Reminder: allow inbound TCP ${PORT} from the Vaier server in this host's security group / firewall."
            """;
    }

    /** Static routes via the relay peer + a systemd oneshot so they survive reboots. */
    private static String routeBlock(String gateway, List<String> cidrs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n# === Routes via relay peer ").append(gateway).append(" ===\n");
        sb.append("# NOTE: routes to other sites' LANs only carry traffic once Vaier issue #250 ships\n");
        sb.append("# (sibling-relay LANs in peer AllowedIPs + relay forwarding). Server-subnet routing works now.\n");
        sb.append("echo \"==> Installing routes via ").append(gateway).append("\"\n");
        for (String cidr : cidrs) {
            sb.append("ip route replace ").append(cidr).append(" via ").append(gateway).append("\n");
        }
        sb.append("\n# Persist across reboots via a systemd oneshot (distro-agnostic, idempotent).\n");
        sb.append("cat > /etc/systemd/system/vaier-lan-routes.service <<'UNIT'\n");
        sb.append("[Unit]\n");
        sb.append("Description=Vaier LAN static routes via relay peer\n");
        sb.append("After=network-online.target\n");
        sb.append("Wants=network-online.target\n");
        sb.append("\n");
        sb.append("[Service]\n");
        sb.append("Type=oneshot\n");
        sb.append("RemainAfterExit=yes\n");
        for (String cidr : cidrs) {
            sb.append("ExecStart=/sbin/ip route replace ").append(cidr).append(" via ").append(gateway).append("\n");
        }
        sb.append("\n");
        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");
        sb.append("UNIT\n");
        sb.append("systemctl daemon-reload\n");
        sb.append("systemctl enable --now vaier-lan-routes.service\n");
        return sb.toString();
    }
}
