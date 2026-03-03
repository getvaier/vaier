package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DockerComposeGeneratorAdapter implements ForGeneratingDockerComposeFiles {

    @Override
    public String generateWireguardClientDockerCompose(DockerComposeConfig config) {
        log.debug("Generating docker-compose for peer: {}", config.peerName());

        return String.format("""
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
                """, config.peerName(), config.peerName(), config.serverUrl(), config.serverPort());
    }
}
