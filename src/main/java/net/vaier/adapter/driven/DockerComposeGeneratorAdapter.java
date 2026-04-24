package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerComposeGeneratorAdapter implements ForGeneratingDockerComposeFiles {

    // Keep in sync with the wireguard image pin in the server's docker-compose.yml.
    static final String WIREGUARD_IMAGE = "lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110";

    @Override
    public String generateWireguardClientDockerCompose(DockerComposeConfig config) {
        log.debug("Generating docker-compose for peer: {}", config.peerName());

        return String.format("""
                services:
                  wireguard-client:
                    image: %s
                    container_name: wireguard-client
                    cap_add:
                      - NET_ADMIN
                      - SYS_MODULE
                    environment:
                      - PUID=1000
                      - PGID=1000
                      - TZ=Europe/Oslo
                    volumes:
                      - ./wireguard-client/config:/config
                      - /lib/modules:/lib/modules:ro
                    sysctls:
                      - net.ipv4.conf.all.src_valid_mark=1
                    restart: unless-stopped

                # Setup Instructions:
                # 1. Create config directory: mkdir -p ./wireguard-client/config
                # 2. Copy peer config from server at ./wireguard/config/%s/%s.conf
                #    to ./wireguard-client/config/wg0.conf
                # 3. Start client: docker-compose up -d
                # 4. Verify connection: docker exec wireguard-client wg show
                #
                # Server: %s:%s
                """, WIREGUARD_IMAGE, config.peerName(), config.peerName(), config.serverUrl(), config.serverPort());
    }
}
