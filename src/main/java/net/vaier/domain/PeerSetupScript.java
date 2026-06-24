package net.vaier.domain;

/**
 * Generates the bootstrap shell script a new VPN peer runs to install Docker, write its
 * WireGuard config and {@code docker-compose.yml}, configure the remote Docker API, and — for a
 * relay peer — install LAN-forwarding iptables rules. A generated client artifact in the same
 * family as {@link WireGuardPeerConfig} and {@link WireguardClientCompose}.
 */
public final class PeerSetupScript {

    private PeerSetupScript() {}

    public static String generate(String peerName, String vpnIp, String serverUrl, String serverPort,
                                  String wgConfig, String lanCidr, String vpnSubnet) {
        return generate(peerName, vpnIp, serverUrl, serverPort, wgConfig, lanCidr, vpnSubnet,
                MachineType.defaultType());
    }

    public static String generate(String peerName, String vpnIp, String serverUrl, String serverPort,
                                  String wgConfig, String lanCidr, String vpnSubnet,
                                  MachineType peerType) {
        var sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("\n");
        sb.append("# Vaier peer setup script for: ").append(peerName).append("\n");
        sb.append("# VPN IP: ").append(vpnIp).append("\n");
        sb.append("# Server: ").append(serverUrl).append(":").append(serverPort).append("\n");
        sb.append("\n");
        sb.append("PEER_NAME=\"").append(peerName).append("\"\n");
        sb.append("VPN_IP=\"").append(vpnIp).append("\"\n");
        sb.append("INSTALL_DIR=\"$HOME/vaier\"\n");
        sb.append("\n");
        sb.append("docker_compose_up() {\n");
        sb.append("  local RETRIES=5\n");
        sb.append("  for i in $(seq 1 $RETRIES); do\n");
        sb.append("    docker compose up -d && return 0\n");
        sb.append("    echo \"docker compose up failed (attempt $i/$RETRIES), retrying in 5s...\"\n");
        sb.append("    sleep 5\n");
        sb.append("  done\n");
        sb.append("  echo 'ERROR: docker compose up failed after $RETRIES attempts'; exit 1\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("echo \"=== Vaier Peer Setup: $PEER_NAME ===\"\n");
        sb.append("echo \"\"\n");
        sb.append("\n");
        sb.append("# --- Install Docker ---\n");
        sb.append("if ! command -v docker &> /dev/null; then\n");
        sb.append("    echo \"Installing Docker...\"\n");
        sb.append("    curl -fsSL https://get.docker.com | sudo sh\n");
        sb.append("    sudo usermod -aG docker \"$USER\"\n");
        sb.append("    echo \"Docker installed.\"\n");
        sb.append("else\n");
        sb.append("    echo \"Docker already installed.\"\n");
        sb.append("fi\n");
        sb.append("sudo systemctl enable docker || true\n");
        sb.append("\n");
        sb.append("# --- Stop any existing services ---\n");
        sb.append("if [ -f \"$INSTALL_DIR/docker-compose.yml\" ]; then\n");
        sb.append("  echo \"Stopping existing services...\"\n");
        sb.append("  cd \"$INSTALL_DIR\" && docker compose down 2>/dev/null || true\n");
        sb.append("fi\n");
        sb.append("\n");
        // wireguard-client runs network_mode: host, so wg0 lives in the host netns.
        // docker compose down does not invoke wg-quick down, so wg0 leaks across reruns —
        // the new container then fails with "wg-quick: wg0 already exists".
        sb.append("# --- Clean up orphaned wg0 interface from any previous run ---\n");
        sb.append("sudo ip link delete wg0 2>/dev/null || true\n");
        sb.append("\n");
        sb.append("# --- Create directory structure ---\n");
        sb.append("echo \"Setting up $INSTALL_DIR...\"\n");
        sb.append("mkdir -p \"$INSTALL_DIR/wireguard-client/config/wg_confs\"\n");
        sb.append("\n");
        sb.append("# --- Write .env file ---\n");
        sb.append("cat > \"$INSTALL_DIR/.env\" << ENV_FILE\n");
        sb.append("PEER_NAME=").append(peerName).append("\n");
        sb.append("VPN_IP=").append(vpnIp).append("\n");
        sb.append("SERVER_URL=").append(serverUrl).append("\n");
        sb.append("SERVER_PORT=").append(serverPort).append("\n");
        sb.append("TZ=Europe/Oslo\n");
        sb.append("ENV_FILE\n");
        sb.append("\n");
        sb.append("echo \"Created .env file\"\n");
        sb.append("\n");
        sb.append("# --- Write WireGuard config ---\n");
        sb.append("cat > \"$INSTALL_DIR/wireguard-client/config/wg_confs/wg0.conf\" << 'WG_CONF'\n");
        sb.append(wgConfig).append("\n");
        sb.append("WG_CONF\n");
        sb.append("\n");
        sb.append("# Force split tunneling: only route VPN subnet through the tunnel\n");
        sb.append("# This prevents SSH and other external traffic from breaking\n");
        sb.append("sed -i 's|AllowedIPs.*=.*0\\.0\\.0\\.0/0.*|AllowedIPs = ").append(vpnSubnet).append("|' \"$INSTALL_DIR/wireguard-client/config/wg_confs/wg0.conf\"\n");
        sb.append("\n");
        sb.append("echo \"Created WireGuard config (split tunneling enabled)\"\n");
        sb.append("\n");
        sb.append("# --- Set sysctl on host (cannot use container sysctls with host network mode) ---\n");
        sb.append("sudo sysctl -w net.ipv4.conf.all.src_valid_mark=1\n");
        sb.append("echo 'net.ipv4.conf.all.src_valid_mark=1' | sudo tee -a /etc/sysctl.d/99-wireguard.conf > /dev/null\n");

        boolean serverType = peerType != null && peerType.isServerType();

        // --- IP forwarding (server-type peers): enabled and persisted exactly once ---
        // Both the relay block and the unconditional internet-egress block (#174) need
        // net.ipv4.ip_forward; write the enable+persist here so it is not duplicated when a peer
        // is both a relay and the internet gateway. Reuses the existing relay sysctl-persist pattern.
        if (serverType) {
            sb.append("\n");
            sb.append("# --- Enable IP forwarding (required for relay and internet-egress NAT) ---\n");
            sb.append("sudo sysctl -w net.ipv4.ip_forward=1\n");
            sb.append("grep -qxF 'net.ipv4.ip_forward=1' /etc/sysctl.d/99-wireguard.conf 2>/dev/null \\\n");
            sb.append("  || echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.d/99-wireguard.conf > /dev/null\n");
        }

        if (lanCidr != null && !lanCidr.isBlank()) {
            String lan = lanCidr.trim();
            sb.append("\n");
            sb.append("# --- Relay peer: forward VPN traffic to LAN ").append(lan).append(" ---\n");
            sb.append("sudo iptables -t nat -C POSTROUTING -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j MASQUERADE 2>/dev/null \\\n");
            sb.append("  || sudo iptables -t nat -A POSTROUTING -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j MASQUERADE\n");
            sb.append("sudo iptables -C FORWARD -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j ACCEPT 2>/dev/null \\\n");
            sb.append("  || sudo iptables -A FORWARD -s ").append(vpnSubnet).append(" -d ").append(lan)
                .append(" -j ACCEPT\n");
            sb.append("sudo iptables -C FORWARD -s ").append(lan).append(" -d ").append(vpnSubnet)
                .append(" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \\\n");
            sb.append("  || sudo iptables -A FORWARD -s ").append(lan).append(" -d ").append(vpnSubnet)
                .append(" -m state --state RELATED,ESTABLISHED -j ACCEPT\n");

            // Persist relay rules across reboots via a systemd oneshot. Distro-agnostic
            // (no iptables-persistent dependency) and idempotent (-C ... || -A ...).
            sb.append("\n");
            sb.append("# --- Persist relay iptables rules across reboot via systemd oneshot ---\n");
            sb.append("sudo tee /etc/systemd/system/vaier-wg-relay-iptables.service > /dev/null << 'UNIT_FILE'\n");
            sb.append("[Unit]\n");
            sb.append("Description=Vaier WireGuard relay iptables rules\n");
            sb.append("After=network-online.target\n");
            sb.append("Wants=network-online.target\n");
            sb.append("\n");
            sb.append("[Service]\n");
            sb.append("Type=oneshot\n");
            sb.append("RemainAfterExit=yes\n");
            sb.append("ExecStart=/bin/sh -c 'iptables -t nat -C POSTROUTING -s ").append(vpnSubnet)
                .append(" -d ").append(lan).append(" -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s ")
                .append(vpnSubnet).append(" -d ").append(lan).append(" -j MASQUERADE'\n");
            sb.append("ExecStart=/bin/sh -c 'iptables -C FORWARD -s ").append(vpnSubnet)
                .append(" -d ").append(lan).append(" -j ACCEPT 2>/dev/null || iptables -A FORWARD -s ")
                .append(vpnSubnet).append(" -d ").append(lan).append(" -j ACCEPT'\n");
            sb.append("ExecStart=/bin/sh -c 'iptables -C FORWARD -s ").append(lan)
                .append(" -d ").append(vpnSubnet).append(" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -A FORWARD -s ")
                .append(lan).append(" -d ").append(vpnSubnet).append(" -m state --state RELATED,ESTABLISHED -j ACCEPT'\n");
            sb.append("\n");
            sb.append("[Install]\n");
            sb.append("WantedBy=multi-user.target\n");
            sb.append("UNIT_FILE\n");
            sb.append("sudo systemctl daemon-reload\n");
            sb.append("sudo systemctl enable --now vaier-wg-relay-iptables.service\n");
        }

        if (serverType) {
            // --- Internet egress (#174): always installed on server-type peers ---
            // Masquerades all VPN-sourced traffic out the host's default-route interface, so this
            // peer can serve as Vaier's central internet gateway. DORMANT unless the Vaier server
            // forwards 0.0.0.0/0 to this peer (only the single designated gateway gets that), so
            // it is harmless on every server peer. No -d filter: NAT everything the server routes
            // here for full-tunnel clients. Egress iface detected at runtime (not baked in) so it
            // survives NIC renames.
            sb.append("\n");
            sb.append("# --- Internet egress NAT: masquerade VPN traffic out the default interface ---\n");
            sb.append("EGRESS_IF=$(ip route show default | grep -oP '(?<=dev )\\S+' | head -1)\n");
            sb.append("if [ -z \"$EGRESS_IF\" ]; then\n");
            sb.append("  echo 'WARNING: could not detect default egress interface; skipping internet-egress NAT'\n");
            sb.append("else\n");
            sb.append("  sudo iptables -t nat -C POSTROUTING -s ").append(vpnSubnet)
                .append(" -o \"$EGRESS_IF\" -j MASQUERADE 2>/dev/null \\\n");
            sb.append("    || sudo iptables -t nat -A POSTROUTING -s ").append(vpnSubnet)
                .append(" -o \"$EGRESS_IF\" -j MASQUERADE\n");
            sb.append("  sudo iptables -C FORWARD -s ").append(vpnSubnet)
                .append(" -o \"$EGRESS_IF\" -j ACCEPT 2>/dev/null \\\n");
            sb.append("    || sudo iptables -A FORWARD -s ").append(vpnSubnet)
                .append(" -o \"$EGRESS_IF\" -j ACCEPT\n");
            sb.append("  sudo iptables -C FORWARD -d ").append(vpnSubnet)
                .append(" -i \"$EGRESS_IF\" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null \\\n");
            sb.append("    || sudo iptables -A FORWARD -d ").append(vpnSubnet)
                .append(" -i \"$EGRESS_IF\" -m state --state RELATED,ESTABLISHED -j ACCEPT\n");
            sb.append("fi\n");

            // Persist egress rules across reboots via a sibling systemd oneshot. The ExecStart lines
            // re-detect $EGRESS_IF at boot rather than baking a possibly-stale iface name into the
            // unit. Idempotent (-C ... || -A ...), distro-agnostic (no iptables-persistent).
            sb.append("\n");
            sb.append("# --- Persist internet-egress iptables rules across reboot via systemd oneshot ---\n");
            sb.append("sudo tee /etc/systemd/system/vaier-wg-egress-iptables.service > /dev/null << 'EGRESS_UNIT'\n");
            sb.append("[Unit]\n");
            sb.append("Description=Vaier WireGuard internet-egress iptables rules\n");
            sb.append("After=network-online.target\n");
            sb.append("Wants=network-online.target\n");
            sb.append("\n");
            sb.append("[Service]\n");
            sb.append("Type=oneshot\n");
            sb.append("RemainAfterExit=yes\n");
            sb.append("ExecStart=/bin/sh -c 'EGRESS_IF=$(ip route show default | grep -oP \"(?<=dev )\\S+\" | head -1); [ -z \"$EGRESS_IF\" ] && exit 0; iptables -t nat -C POSTROUTING -s ")
                .append(vpnSubnet).append(" -o \"$EGRESS_IF\" -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s ")
                .append(vpnSubnet).append(" -o \"$EGRESS_IF\" -j MASQUERADE'\n");
            sb.append("ExecStart=/bin/sh -c 'EGRESS_IF=$(ip route show default | grep -oP \"(?<=dev )\\S+\" | head -1); [ -z \"$EGRESS_IF\" ] && exit 0; iptables -C FORWARD -s ")
                .append(vpnSubnet).append(" -o \"$EGRESS_IF\" -j ACCEPT 2>/dev/null || iptables -A FORWARD -s ")
                .append(vpnSubnet).append(" -o \"$EGRESS_IF\" -j ACCEPT'\n");
            sb.append("ExecStart=/bin/sh -c 'EGRESS_IF=$(ip route show default | grep -oP \"(?<=dev )\\S+\" | head -1); [ -z \"$EGRESS_IF\" ] && exit 0; iptables -C FORWARD -d ")
                .append(vpnSubnet).append(" -i \"$EGRESS_IF\" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -A FORWARD -d ")
                .append(vpnSubnet).append(" -i \"$EGRESS_IF\" -m state --state RELATED,ESTABLISHED -j ACCEPT'\n");
            sb.append("\n");
            sb.append("[Install]\n");
            sb.append("WantedBy=multi-user.target\n");
            sb.append("EGRESS_UNIT\n");
            sb.append("sudo systemctl daemon-reload\n");
            sb.append("sudo systemctl enable --now vaier-wg-egress-iptables.service\n");
        }

        sb.append("\n");
        sb.append("# --- Write docker-compose.yml ---\n");
        sb.append("cat > \"$INSTALL_DIR/docker-compose.yml\" << 'COMPOSE'\n");
        sb.append(WireguardClientCompose.hostNetwork());
        sb.append("COMPOSE\n");
        sb.append("\n");
        sb.append("echo \"Created docker-compose.yml\"\n");
        sb.append("\n");
        sb.append("# --- Configure Docker remote API (bind to 0.0.0.0, firewall restricts to VPN) ---\n");
        sb.append("echo \"Configuring Docker daemon for remote access...\"\n");
        sb.append("if snap list docker &>/dev/null; then\n");
        sb.append("  echo \"Detected snap Docker — writing config to snap path\"\n");
        sb.append("  sudo tee /var/snap/docker/current/config/daemon.json > /dev/null << 'DAEMON_JSON'\n");
        sb.append("{\n");
        sb.append("    \"hosts\": [\"unix:///var/run/docker.sock\", \"tcp://0.0.0.0:2375\"]\n");
        sb.append("}\n");
        sb.append("DAEMON_JSON\n");
        sb.append("  echo \"Restarting snap Docker daemon...\"\n");
        sb.append("  sudo systemctl restart snap.docker.dockerd || true\n");
        sb.append("else\n");
        sb.append("  sudo mkdir -p /etc/docker\n");
        sb.append("  sudo tee /etc/docker/daemon.json > /dev/null << 'DAEMON_JSON'\n");
        sb.append("{\n");
        sb.append("    \"hosts\": [\"unix:///var/run/docker.sock\", \"tcp://0.0.0.0:2375\"]\n");
        sb.append("}\n");
        sb.append("DAEMON_JSON\n");
        sb.append("  # Override systemd to not pass -H flag (conflicts with daemon.json hosts)\n");
        sb.append("  sudo mkdir -p /etc/systemd/system/docker.service.d\n");
        sb.append("  sudo tee /etc/systemd/system/docker.service.d/override.conf > /dev/null << 'OVERRIDE'\n");
        sb.append("[Service]\n");
        sb.append("ExecStart=\n");
        sb.append("ExecStart=/usr/bin/dockerd\n");
        sb.append("OVERRIDE\n");
        sb.append("  echo \"Reloading Docker daemon...\"\n");
        sb.append("  sudo systemctl daemon-reload || true\n");
        sb.append("  sudo systemctl restart docker || sudo service docker restart || true\n");
        sb.append("fi\n");
        sb.append("WAIT=0; until docker info > /dev/null 2>&1; do\n");
        sb.append("  if [ $WAIT -ge 30 ]; then\n");
        sb.append("    echo 'ERROR: Docker failed to start. Status:'; sudo systemctl status docker --no-pager; exit 1\n");
        sb.append("  fi\n");
        sb.append("  sleep 1; WAIT=$((WAIT+1))\n");
        sb.append("done\n");
        sb.append("\n");
        sb.append("# --- Firewall: only allow Docker API from VPN subnet ---\n");
        sb.append("echo \"Configuring firewall to restrict Docker API to VPN network...\"\n");
        sb.append("sudo iptables -D INPUT -p tcp --dport 2375 -j DROP 2>/dev/null || true\n");
        sb.append("sudo iptables -D INPUT -p tcp --dport 2375 -s ").append(vpnSubnet).append(" -j ACCEPT 2>/dev/null || true\n");
        sb.append("sudo iptables -A INPUT -p tcp --dport 2375 -s ").append(vpnSubnet).append(" -j ACCEPT\n");
        sb.append("sudo iptables -A INPUT -p tcp --dport 2375 -j DROP\n");
        sb.append("\n");
        sb.append("# Persist iptables rules across reboots\n");
        sb.append("if command -v netfilter-persistent &> /dev/null; then\n");
        sb.append("    sudo netfilter-persistent save\n");
        sb.append("elif command -v iptables-save &> /dev/null; then\n");
        sb.append("    sudo sh -c 'iptables-save > /etc/iptables/rules.v4' 2>/dev/null || true\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# --- Start all services ---\n");
        sb.append("echo \"Starting all services...\"\n");
        sb.append("cd \"$INSTALL_DIR\"\n");
        sb.append("docker_compose_up\n");
        sb.append("\n");
        sb.append("echo \"Waiting for VPN tunnel to establish...\"\n");
        sb.append("sleep 5\n");
        sb.append("if ! ip addr show | grep -q \"$VPN_IP\"; then\n");
        sb.append("    echo \"WARNING: VPN IP $VPN_IP not yet visible. Waiting longer...\"\n");
        sb.append("    sleep 10\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# WireGuard runs in host network mode, so it survives Docker restart\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"=== Setup complete ===\"\n");
        sb.append("echo \"  Install dir:  $INSTALL_DIR\"\n");
        sb.append("echo \"  VPN IP:       $VPN_IP\"\n");
        sb.append("echo \"  Docker API:   tcp://0.0.0.0:2375 (firewalled to VPN subnet ").append(vpnSubnet).append(")\"\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"Verify VPN connection:\"\n");
        sb.append("echo \"  docker exec wireguard-client wg show\"\n");
        return sb.toString();
    }
}
