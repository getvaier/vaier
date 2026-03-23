package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GeneratePeerSetupScriptUseCase;
import net.vaier.application.GetPeerConfigUseCase;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeneratePeerSetupScriptService implements GeneratePeerSetupScriptUseCase {

    private final GetPeerConfigUseCase getPeerConfigUseCase;

    @Override
    public Optional<String> generateSetupScript(String peerName, String serverUrl, String serverPort) {
        log.info("Generating setup script for peer: {}", peerName);

        return getPeerConfigUseCase.getPeerConfig(peerName).map(peerConfig -> {
            String vpnIp = peerConfig.ipAddress();
            String wgConfig = peerConfig.configContent();

            return generateScript(peerName, vpnIp, serverUrl, serverPort, wgConfig);
        });
    }

    private String generateScript(String peerName, String vpnIp, String serverUrl, String serverPort, String wgConfig) {
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
        sb.append("echo \"=== Vaier Peer Setup: $PEER_NAME ===\"\n");
        sb.append("echo \"\"\n");
        sb.append("\n");
        sb.append("# --- Install Docker ---\n");
        sb.append("if ! command -v docker &> /dev/null; then\n");
        sb.append("    echo \"Installing Docker...\"\n");
        sb.append("    curl -fsSL https://get.docker.com | sudo sh\n");
        sb.append("    sudo usermod -aG docker \"$USER\"\n");
        sb.append("    echo \"Docker installed. You may need to log out and back in for group membership.\"\n");
        sb.append("else\n");
        sb.append("    echo \"Docker already installed.\"\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("# --- Configure Docker remote API on VPN interface ---\n");
        sb.append("echo \"Configuring Docker daemon for remote access on VPN network...\"\n");
        sb.append("sudo mkdir -p /etc/docker\n");
        sb.append("\n");
        sb.append("if [ -f /etc/docker/daemon.json ] && [ -s /etc/docker/daemon.json ]; then\n");
        sb.append("    sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.bak\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("sudo tee /etc/docker/daemon.json > /dev/null << 'DAEMON_JSON'\n");
        sb.append("{\n");
        sb.append("    \"hosts\": [\"unix:///var/run/docker.sock\", \"tcp://").append(vpnIp).append(":2375\"],\n");
        sb.append("    \"iptables\": true\n");
        sb.append("}\n");
        sb.append("DAEMON_JSON\n");
        sb.append("\n");
        sb.append("# Override systemd to not pass -H flag (conflicts with daemon.json hosts)\n");
        sb.append("sudo mkdir -p /etc/systemd/system/docker.service.d\n");
        sb.append("sudo tee /etc/systemd/system/docker.service.d/override.conf > /dev/null << 'OVERRIDE'\n");
        sb.append("[Service]\n");
        sb.append("ExecStart=\n");
        sb.append("ExecStart=/usr/bin/dockerd\n");
        sb.append("OVERRIDE\n");
        sb.append("\n");
        sb.append("echo \"Docker daemon will listen on tcp://$VPN_IP:2375 (VPN only)\"\n");
        sb.append("\n");
        sb.append("# --- Create directory structure ---\n");
        sb.append("echo \"Setting up $INSTALL_DIR...\"\n");
        sb.append("mkdir -p \"$INSTALL_DIR/wireguard-client/config\"\n");
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
        sb.append("cat > \"$INSTALL_DIR/wireguard-client/config/wg0.conf\" << 'WG_CONF'\n");
        sb.append(wgConfig).append("\n");
        sb.append("WG_CONF\n");
        sb.append("\n");
        sb.append("echo \"Created WireGuard config\"\n");
        sb.append("\n");
        sb.append("# --- Write docker-compose.yml ---\n");
        sb.append("cat > \"$INSTALL_DIR/docker-compose.yml\" << 'COMPOSE'\n");
        sb.append("services:\n");
        sb.append("  wireguard-client:\n");
        sb.append("    image: lscr.io/linuxserver/wireguard:latest\n");
        sb.append("    container_name: wireguard-client\n");
        sb.append("    cap_add:\n");
        sb.append("      - NET_ADMIN\n");
        sb.append("      - SYS_MODULE\n");
        sb.append("    environment:\n");
        sb.append("      - PUID=1000\n");
        sb.append("      - PGID=1000\n");
        sb.append("      - TZ=${TZ:-Europe/Oslo}\n");
        sb.append("    volumes:\n");
        sb.append("      - ./wireguard-client/config:/config\n");
        sb.append("      - /lib/modules:/lib/modules:ro\n");
        sb.append("    sysctls:\n");
        sb.append("      - net.ipv4.conf.all.src_valid_mark=1\n");
        sb.append("    restart: unless-stopped\n");
        sb.append("    network_mode: host\n");
        sb.append("COMPOSE\n");
        sb.append("\n");
        sb.append("echo \"Created docker-compose.yml\"\n");
        sb.append("\n");
        sb.append("# --- Reload Docker and start services ---\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"Reloading Docker daemon...\"\n");
        sb.append("sudo systemctl daemon-reload\n");
        sb.append("sudo systemctl restart docker\n");
        sb.append("\n");
        sb.append("echo \"Starting WireGuard client...\"\n");
        sb.append("cd \"$INSTALL_DIR\"\n");
        sb.append("docker compose up -d\n");
        sb.append("\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"=== Setup complete ===\"\n");
        sb.append("echo \"  Install dir:  $INSTALL_DIR\"\n");
        sb.append("echo \"  VPN IP:       $VPN_IP\"\n");
        sb.append("echo \"  Docker API:   tcp://$VPN_IP:2375 (accessible from VPN)\"\n");
        sb.append("echo \"\"\n");
        sb.append("echo \"Verify VPN connection:\"\n");
        sb.append("echo \"  docker exec wireguard-client wg show\"\n");
        return sb.toString();
    }
}
