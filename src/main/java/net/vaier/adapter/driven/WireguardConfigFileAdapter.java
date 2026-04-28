package net.vaier.adapter.driven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class WireguardConfigFileAdapter implements ForGettingPeerConfigurations, ForResolvingPeerNames, ForUpdatingPeerConfigurations {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record VaierMetadata(String peerType, String lanCidr, String lanAddress) {
        VaierMetadata() { this(null, null, null); }
    }

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
            VaierMetadata meta = extractVaierMetadata(configContent);

            return Optional.of(new PeerConfiguration(peerName, ipAddress, configContent,
                    parsePeerType(meta.peerType()), meta.lanCidr(), meta.lanAddress()));
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
                VaierMetadata meta = extractVaierMetadata(configContent);

                return Optional.of(new PeerConfiguration(peerName, ipAddress, configContent,
                        parsePeerType(meta.peerType()), meta.lanCidr(), meta.lanAddress()));
            }
        } catch (Exception e) {
            log.error("Failed to find peer by IP {}: {}", ipAddress, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<PeerConfiguration> getAllPeerConfigs() {
        List<PeerConfiguration> configs = new ArrayList<>();
        Path configDir = Paths.get(wireguardConfigPath);

        if (!Files.exists(configDir)) {
            return configs;
        }

        try (var stream = Files.list(configDir)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals("wg_confs"))
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        String peerName = dir.getFileName().toString();
                        getPeerConfigByName(peerName).ifPresent(configs::add);
                    });
        } catch (Exception e) {
            log.error("Failed to list peer configs: {}", e.getMessage(), e);
        }

        return configs;
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

    private VaierMetadata extractVaierMetadata(String configContent) {
        for (String line : configContent.split("\n")) {
            if (line.trim().startsWith("# VAIER:")) {
                String json = line.substring(line.indexOf(':') + 1).trim();
                try {
                    return OBJECT_MAPPER.readValue(json, VaierMetadata.class);
                } catch (Exception e) {
                    log.warn("Failed to parse VAIER metadata: {}", e.getMessage());
                }
            }
        }
        return new VaierMetadata();
    }

    @Override
    public void updateLanAddress(String peerName, String lanAddress) {
        Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
        if (!Files.exists(peerConfigPath)) {
            throw new IllegalArgumentException("Peer not found: " + peerName);
        }
        try {
            String content = Files.readString(peerConfigPath);
            VaierMetadata existing = extractVaierMetadata(content);
            String normalized = (lanAddress == null || lanAddress.isBlank()) ? null : lanAddress.trim();
            VaierMetadata updated = new VaierMetadata(
                existing.peerType() != null ? existing.peerType() : PeerType.UBUNTU_SERVER.name(),
                existing.lanCidr(),
                normalized);
            String newLine = "# VAIER: " + OBJECT_MAPPER.writeValueAsString(updated);

            String rewritten;
            if (content.contains("# VAIER:")) {
                rewritten = content.replaceAll("(?m)^# VAIER:.*$", java.util.regex.Matcher.quoteReplacement(newLine));
            } else {
                rewritten = newLine + "\n" + content;
            }
            Files.writeString(peerConfigPath, rewritten);
            log.info("Updated lanAddress for peer {} to {}", peerName, normalized);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update lanAddress for peer " + peerName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void updateLanCidr(String peerName, String lanCidr) {
        Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
        if (!Files.exists(peerConfigPath)) {
            throw new IllegalArgumentException("Peer not found: " + peerName);
        }
        try {
            String content = Files.readString(peerConfigPath);
            VaierMetadata existing = extractVaierMetadata(content);
            String normalized = (lanCidr == null || lanCidr.isBlank()) ? null : lanCidr.trim();
            VaierMetadata updated = new VaierMetadata(
                existing.peerType() != null ? existing.peerType() : PeerType.UBUNTU_SERVER.name(),
                normalized,
                existing.lanAddress());
            String newLine = "# VAIER: " + OBJECT_MAPPER.writeValueAsString(updated);

            String rewritten;
            if (content.contains("# VAIER:")) {
                rewritten = content.replaceAll("(?m)^# VAIER:.*$", java.util.regex.Matcher.quoteReplacement(newLine));
            } else {
                rewritten = newLine + "\n" + content;
            }
            Files.writeString(peerConfigPath, rewritten);
            log.info("Updated lanCidr for peer {} to {}", peerName, normalized);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update lanCidr for peer " + peerName + ": " + e.getMessage(), e);
        }
    }

    private PeerType parsePeerType(String value) {
        if (value == null) return PeerType.UBUNTU_SERVER;
        try {
            return PeerType.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown peer type '{}', defaulting to UBUNTU_SERVER", value);
            return PeerType.UBUNTU_SERVER;
        }
    }
}
