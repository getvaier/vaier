package net.vaier.adapter.driven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForRenamingVpnPeers;
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
public class WireguardConfigFileAdapter implements ForGettingPeerConfigurations, ForResolvingPeerNames,
        ForUpdatingPeerConfigurations, ForRenamingVpnPeers {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${wireguard.config.path:/wireguard/config}")
    private String wireguardConfigPath;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record VaierMetadata(String peerType, String lanCidr, String lanAddress, String description) {
        VaierMetadata() { this(null, null, null, null); }
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
                    parseMachineType(meta.peerType()), meta.lanCidr(), meta.lanAddress(), meta.description()));
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
                        parseMachineType(meta.peerType()), meta.lanCidr(), meta.lanAddress(), meta.description()));
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
        String normalized = blankToNull(lanAddress);
        rewriteVaierMetadata(peerName, "lanAddress", normalized,
            existing -> new VaierMetadata(existing.peerType(), existing.lanCidr(),
                normalized, existing.description()));
    }

    @Override
    public void updateLanCidr(String peerName, String lanCidr) {
        String normalized = blankToNull(lanCidr);
        rewriteVaierMetadata(peerName, "lanCidr", normalized,
            existing -> new VaierMetadata(existing.peerType(), normalized,
                existing.lanAddress(), existing.description()));
    }

    @Override
    public void updateDescription(String peerName, String description) {
        String normalized = blankToNull(description);
        rewriteVaierMetadata(peerName, "description", normalized,
            existing -> new VaierMetadata(existing.peerType(), existing.lanCidr(),
                existing.lanAddress(), normalized));
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Rewrites the single-line {@code # VAIER:} metadata comment in a peer's {@code .conf},
     * preserving every field the {@code mutator} does not touch. Adds the comment if missing.
     */
    private void rewriteVaierMetadata(String peerName, String fieldName, String newValue,
                                      java.util.function.UnaryOperator<VaierMetadata> mutator) {
        Path peerConfigPath = Paths.get(wireguardConfigPath, peerName, peerName + ".conf");
        if (!Files.exists(peerConfigPath)) {
            throw new net.vaier.domain.PeerNotFoundException("Peer not found: " + peerName);
        }
        try {
            String content = Files.readString(peerConfigPath);
            VaierMetadata existing = extractVaierMetadata(content);
            // A peer without a # VAIER comment predates metadata — default its type so the
            // rewritten comment is well-formed rather than missing peerType entirely.
            VaierMetadata withType = new VaierMetadata(
                existing.peerType() != null ? existing.peerType() : MachineType.UBUNTU_SERVER.name(),
                existing.lanCidr(), existing.lanAddress(), existing.description());
            VaierMetadata updated = mutator.apply(withType);
            String newLine = "# VAIER: " + OBJECT_MAPPER.writeValueAsString(updated);

            String rewritten;
            if (content.contains("# VAIER:")) {
                rewritten = content.replaceAll("(?m)^# VAIER:.*$", java.util.regex.Matcher.quoteReplacement(newLine));
            } else {
                rewritten = newLine + "\n" + content;
            }
            Files.writeString(peerConfigPath, rewritten);
            log.info("Updated {} for peer {} to {}", fieldName, peerName, newValue);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to update " + fieldName + " for peer " + peerName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void renamePeer(String currentName, String newName) {
        Path oldDir = Paths.get(wireguardConfigPath, currentName);
        Path newDir = Paths.get(wireguardConfigPath, newName);
        if (!Files.isDirectory(oldDir)) {
            throw new net.vaier.domain.PeerNotFoundException("Peer not found: " + currentName);
        }
        if (Files.exists(newDir)) {
            throw new IllegalStateException("A peer named " + newName + " already exists");
        }
        try {
            // Move the directory first, then the .conf inside it — the running tunnel keys peers
            // by public key in wg0.conf, so these per-peer artefacts can move without disruption.
            Files.move(oldDir, newDir);
            Path oldConf = newDir.resolve(currentName + ".conf");
            if (Files.exists(oldConf)) {
                Files.move(oldConf, newDir.resolve(newName + ".conf"));
            }
            log.info("Renamed peer directory {} to {}", currentName, newName);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to rename peer " + currentName + " to " + newName + ": " + e.getMessage(), e);
        }
    }

    private MachineType parseMachineType(String value) {
        if (value == null) return MachineType.UBUNTU_SERVER;
        try {
            return MachineType.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown peer type '{}', defaulting to UBUNTU_SERVER", value);
            return MachineType.UBUNTU_SERVER;
        }
    }
}
