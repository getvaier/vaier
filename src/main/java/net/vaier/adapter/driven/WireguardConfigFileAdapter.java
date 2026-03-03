package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForResolvingPeerNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@Slf4j
public class WireguardConfigFileAdapter implements ForGettingPeerConfigurations, ForResolvingPeerNames {

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @Override
    public Optional<PeerConfiguration> getPeerConfigByName(String peerName) {
        try {
            Path configDir = Paths.get(wireguardConfigPath);
            Path peerConfigPath = configDir.resolve(peerName).resolve(peerName + ".conf");

            if (!Files.exists(peerConfigPath)) {
                log.warn("Peer config not found: {}", peerConfigPath);
                return Optional.empty();
            }

            String configContent = Files.readString(peerConfigPath);
            String ipAddress = extractIpAddress(configContent);

            return Optional.of(new PeerConfiguration(peerName, ipAddress, configContent));
        } catch (Exception e) {
            log.error("Failed to read peer config: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<PeerConfiguration> getPeerConfigByIp(String ipAddress) {
        try {
            Path configDir = Paths.get(wireguardConfigPath);
            log.info("Searching for peer with IP {} in directory: {}", ipAddress, configDir.toAbsolutePath());

            if (!Files.exists(configDir)) {
                log.warn("Config directory does not exist: {}", configDir.toAbsolutePath());
                return Optional.empty();
            }

            try (var stream = Files.list(configDir)) {
                Optional<Path> foundPeerDir = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals("wg_confs"))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> matchesIpAddress(dir, ipAddress))
                    .findFirst();

                if (foundPeerDir.isEmpty()) {
                    log.warn("No peer directory found for IP: {}", ipAddress);
                    return Optional.empty();
                }

                String peerName = foundPeerDir.get().getFileName().toString();
                Path peerConfigPath = foundPeerDir.get().resolve(peerName + ".conf");
                String configContent = Files.readString(peerConfigPath);

                return Optional.of(new PeerConfiguration(peerName, ipAddress, configContent));
            }
        } catch (Exception e) {
            log.error("Failed to find peer by IP {}: {}", ipAddress, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public String resolvePeerNameByIp(String ipAddress) {
        try {
            Path configDir = Paths.get(wireguardConfigPath);
            log.debug("Searching for peer with IP {} in directory: {}", ipAddress, configDir.toAbsolutePath());

            if (!Files.exists(configDir)) {
                log.warn("Config directory does not exist: {}", configDir.toAbsolutePath());
                return ipAddress;
            }

            try (var stream = Files.list(configDir)) {
                return stream
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals("wg_confs"))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> matchesIpAddress(dir, ipAddress))
                    .map(path -> path.getFileName().toString())
                    .findFirst()
                    .orElseGet(() -> {
                        log.warn("No peer directory found for IP: {}", ipAddress);
                        return ipAddress;
                    });
            }
        } catch (Exception e) {
            log.error("Error finding peer name for IP {}: {}", ipAddress, e.getMessage(), e);
            return ipAddress;
        }
    }

    private boolean matchesIpAddress(Path peerDir, String ipAddress) {
        try {
            String dirName = peerDir.getFileName().toString();
            Path confFile = peerDir.resolve(dirName + ".conf");
            log.debug("Checking config file: {}", confFile);

            if (Files.exists(confFile)) {
                String content = Files.readString(confFile);
                String foundIp = extractIpAddress(content);
                log.debug("Found IP {} in peer {}", foundIp, dirName);
                return foundIp.equals(ipAddress);
            } else {
                log.debug("Config file does not exist: {}", confFile);
            }
        } catch (Exception e) {
            log.warn("Error checking peer dir {}: {}", peerDir, e.getMessage());
        }
        return false;
    }

    private String extractIpAddress(String configContent) {
        for (String line : configContent.split("\n")) {
            if (line.trim().startsWith("Address")) {
                String address = line.substring(line.indexOf('=') + 1).trim();
                return address.split("/")[0];
            }
        }
        return "";
    }
}
