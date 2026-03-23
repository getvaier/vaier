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
        return """
                #!/bin/bash
                set -euo pipefail

                # Vaier peer setup script for: %s
                # VPN IP: %s
                # Server: %s:%s

                PEER_NAME="%s"
                VPN_IP="%s"
                INSTALL_DIR="$HOME/vaier"

                echo "=== Vaier Peer Setup: $PEER_NAME ==="
                echo ""

                # --- Install Docker ---
                if ! command -v docker &> /dev/null; then
                    echo "Installing Docker..."
                    curl -fsSL https://get.docker.com | sudo sh
                    sudo usermod -aG docker "$USER"
                    echo "Docker installed. You may need to log out and back in for group membership."
                else
                    echo "Docker already installed."
                fi

                # --- Configure Docker remote API on VPN interface ---
                echo "Configuring Docker daemon for remote access on VPN network..."
                sudo mkdir -p /etc/docker

                # Preserve existing config and add/update the hosts entry
                if [ -f /etc/docker/daemon.json ] && [ -s /etc/docker/daemon.json ]; then
                    # Backup existing config
                    sudo cp /etc/docker/daemon.json /etc/docker/daemon.json.bak
                fi

                sudo tee /etc/docker/daemon.json > /dev/null << 'DAEMON_JSON'
                {
                    "hosts": ["unix:///var/run/docker.sock", "tcp://%s:2375"],
                    "iptables": true
                }
                DAEMON_JSON

                # Override systemd to not pass -H flag (conflicts with daemon.json hosts)
                sudo mkdir -p /etc/systemd/system/docker.service.d
                sudo tee /etc/systemd/system/docker.service.d/override.conf > /dev/null << 'OVERRIDE'
                [Service]
                ExecStart=
                ExecStart=/usr/bin/dockerd
                OVERRIDE

                echo "Docker daemon will listen on tcp://$VPN_IP:2375 (VPN only)"

                # --- Create directory structure ---
                echo "Setting up $INSTALL_DIR..."
                mkdir -p "$INSTALL_DIR/wireguard-client/config"

                # --- Write .env file ---
                cat > "$INSTALL_DIR/.env" << ENV_FILE
                PEER_NAME=%s
                VPN_IP=%s
                SERVER_URL=%s
                SERVER_PORT=%s
                TZ=Europe/Oslo
                ENV_FILE

                echo "Created .env file"

                # --- Write WireGuard config ---
                cat > "$INSTALL_DIR/wireguard-client/config/wg0.conf" << 'WG_CONF'
                %s
                WG_CONF

                echo "Created WireGuard config"

                # --- Write docker-compose.yml ---
                cat > "$INSTALL_DIR/docker-compose.yml" << 'COMPOSE'
                services:
                  wireguard-client:
                    image: lscr.io/linuxserver/wireguard:latest
                    container_name: wireguard-client
                    cap_add:
                      - NET_ADMIN
                      - SYS_MODULE
                    environment:
                      - PUID=1000
                      - PGID=1000
                      - TZ=${TZ:-Europe/Oslo}
                    volumes:
                      - ./wireguard-client/config:/config
                      - /lib/modules:/lib/modules:ro
                    sysctls:
                      - net.ipv4.conf.all.src_valid_mark=1
                    restart: unless-stopped
                    network_mode: host
                COMPOSE

                echo "Created docker-compose.yml"

                # --- Reload Docker and start services ---
                echo ""
                echo "Reloading Docker daemon..."
                sudo systemctl daemon-reload
                sudo systemctl restart docker

                echo "Starting WireGuard client..."
                cd "$INSTALL_DIR"
                docker compose up -d

                echo ""
                echo "=== Setup complete ==="
                echo "  Install dir:  $INSTALL_DIR"
                echo "  VPN IP:       $VPN_IP"
                echo "  Docker API:   tcp://$VPN_IP:2375 (accessible from VPN)"
                echo ""
                echo "Verify VPN connection:"
                echo "  docker exec wireguard-client wg show"
                """.formatted(
                peerName, vpnIp, serverUrl, serverPort,
                peerName, vpnIp,
                vpnIp,
                peerName, vpnIp, serverUrl, serverPort,
                wgConfig
        );
    }
}
