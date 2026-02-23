package com.wireweave.adapter.driven;

import com.wireweave.domain.WireguardConfig;
import com.wireweave.domain.port.ForManagingWireguardConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class WireguardAdapter implements ForManagingWireguardConfig {

    @Value("${wireguard.config.path:c:/tmp/wireguard}")
    private String configBasePath;

    @Value("${wireguard.wg.executable:C:/Program Files/WireGuard/wg.exe}")
    private String wgExecutable;

    @Override
    public WireguardConfig getConfig(String interfaceName) {
        String configPath = getConfigFilePath(interfaceName);
        return parseConfig(configPath);
    }

    @Override
    public void createPeer(String interfaceName, WireguardConfig.WireguardPeer peer) {
        WireguardConfig config = getConfig(interfaceName);
        List<WireguardConfig.WireguardPeer> peers = new ArrayList<>(config.getPeers());

        // Check if peer already exists
        if (peers.stream().anyMatch(p -> p.getPublicKey().equals(peer.getPublicKey()))) {
            throw new IllegalArgumentException("Peer with public key " + peer.getPublicKey() + " already exists");
        }

        peers.add(peer);
        WireguardConfig updatedConfig = new WireguardConfig(config.getInterfaceConfig(), peers);
        saveConfig(interfaceName, updatedConfig);
    }

    @Override
    public void updatePeer(String interfaceName, String publicKey, WireguardConfig.WireguardPeer updatedPeer) {
        WireguardConfig config = getConfig(interfaceName);
        List<WireguardConfig.WireguardPeer> peers = new ArrayList<>(config.getPeers());

        boolean found = false;
        for (int i = 0; i < peers.size(); i++) {
            if (peers.get(i).getPublicKey().equals(publicKey)) {
                peers.set(i, updatedPeer);
                found = true;
                break;
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Peer with public key " + publicKey + " not found");
        }

        WireguardConfig updatedConfig = new WireguardConfig(config.getInterfaceConfig(), peers);
        saveConfig(interfaceName, updatedConfig);
    }

    @Override
    public void deletePeer(String interfaceName, String publicKey) {
        WireguardConfig config = getConfig(interfaceName);
        List<WireguardConfig.WireguardPeer> peers = config.getPeers().stream()
                .filter(p -> !p.getPublicKey().equals(publicKey))
                .collect(Collectors.toList());

        if (peers.size() == config.getPeers().size()) {
            throw new IllegalArgumentException("Peer with public key " + publicKey + " not found");
        }

        WireguardConfig updatedConfig = new WireguardConfig(config.getInterfaceConfig(), peers);
        saveConfig(interfaceName, updatedConfig);
    }

    @Override
    public List<WireguardConfig.WireguardPeer> getPeers(String interfaceName) {
        return getConfig(interfaceName).getPeers();
    }

    @Override
    public void applyConfig(String interfaceName) {
        try {
            String configPath = getConfigFilePath(interfaceName);

            // Check if wg.exe exists
            File wgFile = new File(wgExecutable);
            if (!wgFile.exists()) {
                throw new RuntimeException("WireGuard executable not found at: " + wgExecutable);
            }

            // Execute: wg syncconf <interface> <config-file>
            ProcessBuilder processBuilder = new ProcessBuilder(
                    wgExecutable,
                    "syncconf",
                    interfaceName,
                    configPath
            );
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Failed to apply WireGuard config. Exit code: " + exitCode + ", Output: " + output);
            }

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to apply WireGuard configuration", e);
        }
    }

    /**
     * Generate a new WireGuard key pair (private key + public key).
     *
     * @return WireguardKeyPair containing both private and public keys
     */
    public WireguardKeyPair generateKeyPair() {
        try {
            // Check if wg.exe exists
            File wgFile = new File(wgExecutable);
            if (!wgFile.exists()) {
                throw new RuntimeException("WireGuard executable not found at: " + wgExecutable);
            }

            // Generate private key using: wg genkey
            String privateKey = executeWgCommand("genkey");

            // Derive public key from private key using: wg pubkey
            String publicKey = derivePublicKey(privateKey);

            return new WireguardKeyPair(privateKey.trim(), publicKey.trim());

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to generate WireGuard key pair", e);
        }
    }

    /**
     * Derive public key from private key.
     *
     * @param privateKey The private key
     * @return The corresponding public key
     */
    private String derivePublicKey(String privateKey) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(wgExecutable, "pubkey");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // Write private key to stdin
        try (OutputStream os = process.getOutputStream()) {
            os.write(privateKey.getBytes());
            os.flush();
        }

        // Read public key from stdout
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to derive public key. Exit code: " + exitCode + ", Output: " + output);
        }

        return output;
    }

    /**
     * Execute a WireGuard command and return its output.
     *
     * @param command The wg subcommand to execute
     * @return The command output
     */
    private String executeWgCommand(String command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(wgExecutable, command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // Read output
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to execute wg " + command + ". Exit code: " + exitCode + ", Output: " + output);
        }

        return output;
    }

    /**
     * Record representing a WireGuard key pair.
     */
    public record WireguardKeyPair(String privateKey, String publicKey) {}

    /**
     * Save WireGuard configuration to file.
     */
    private void saveConfig(String interfaceName, WireguardConfig config) {
        String configPath = getConfigFilePath(interfaceName);
        Path path = Paths.get(configPath);

        // Create backup
        try {
            if (Files.exists(path)) {
                Path backup = Paths.get(configPath + ".backup");
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create backup of config file", e);
        }

        // Write new config
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configPath))) {
            // Write [Interface] section
            WireguardConfig.WireguardInterface iface = config.getInterfaceConfig();
            writer.write("[Interface]\n");
            if (iface.getAddress() != null) {
                writer.write("Address = " + iface.getAddress() + "\n");
            }
            if (iface.getListenPort() != null) {
                writer.write("ListenPort = " + iface.getListenPort() + "\n");
            }
            for (String postUp : iface.getPostUpCommands()) {
                writer.write("PostUp = " + postUp + "\n");
            }
            for (String postDown : iface.getPostDownCommands()) {
                writer.write("PostDown = " + postDown + "\n");
            }
            writer.write("\n");

            // Write [Peer] sections
            for (WireguardConfig.WireguardPeer peer : config.getPeers()) {
                writer.write("# " + peer.getName() + "\n");
                writer.write("[Peer]\n");
                writer.write("PublicKey = " + peer.getPublicKey() + "\n");
                if (peer.getAllowedIPs() != null) {
                    writer.write("AllowedIPs = " + peer.getAllowedIPs() + "\n");
                }
                if (peer.getEndpoint() != null) {
                    writer.write("Endpoint = " + peer.getEndpoint() + "\n");
                }
                if (peer.getPersistentKeepalive() != null) {
                    writer.write("PersistentKeepalive = " + peer.getPersistentKeepalive() + "\n");
                }
                writer.write("\n");
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to write WireGuard configuration file: " + configPath, e);
        }
    }

    /**
     * Get the full path to a WireGuard configuration file.
     */
    private String getConfigFilePath(String interfaceName) {
        return configBasePath + "/" + interfaceName + ".conf";
    }

    /**
     * Parse a WireGuard configuration file.
     *
     * @param configPath Path to the WireGuard configuration file
     * @return WireguardConfig object containing interface and peer information
     */
    public WireguardConfig parseConfig(String configPath) {
        File configFile = new File(configPath);

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            WireguardConfig.WireguardInterface interfaceConfig = null;
            List<WireguardConfig.WireguardPeer> peers = new ArrayList<>();

            String currentSection = null;
            String address = null;
            Integer listenPort = null;
            String privateKeyPath = null;
            List<String> postUpCommands = new ArrayList<>();
            List<String> postDownCommands = new ArrayList<>();

            String peerComment = null;
            String publicKey = null;
            String allowedIPs = null;
            String endpoint = null;
            Integer persistentKeepalive = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines
                if (line.isEmpty()) {
                    continue;
                }

                // Check for section headers
                if (line.equals("[Interface]")) {
                    currentSection = "Interface";
                    continue;
                } else if (line.equals("[Peer]")) {
                    // Save previous peer if exists
                    if (currentSection != null && currentSection.equals("Peer") && publicKey != null) {
                        peers.add(new WireguardConfig.WireguardPeer(
                            peerComment != null ? peerComment : "unknown",
                            publicKey,
                            allowedIPs,
                            endpoint,
                            persistentKeepalive
                        ));
                    }

                    // Start new peer
                    currentSection = "Peer";
                    peerComment = null;
                    publicKey = null;
                    allowedIPs = null;
                    endpoint = null;
                    persistentKeepalive = null;
                    continue;
                }

                // Handle comments (peer names)
                if (line.startsWith("#")) {
                    String comment = line.substring(1).trim();
                    if (currentSection != null && currentSection.equals("Peer") && !comment.isEmpty()) {
                        peerComment = comment;
                    }
                    continue;
                }

                // Parse key-value pairs
                String[] parts = line.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }

                String key = parts[0].trim();
                String value = parts[1].trim();

                if (currentSection != null && currentSection.equals("Interface")) {
                    switch (key) {
                        case "Address":
                            address = value;
                            break;
                        case "ListenPort":
                            listenPort = Integer.parseInt(value);
                            break;
                        case "PostUp":
                            postUpCommands.add(value);
                            // Extract private key path if present
                            if (value.contains("private-key")) {
                                privateKeyPath = extractPrivateKeyPath(value);
                            }
                            break;
                        case "PostDown":
                            postDownCommands.add(value);
                            break;
                    }
                } else if (currentSection != null && currentSection.equals("Peer")) {
                    switch (key) {
                        case "PublicKey":
                            publicKey = value;
                            break;
                        case "AllowedIPs":
                            allowedIPs = value;
                            break;
                        case "Endpoint":
                            endpoint = value;
                            break;
                        case "PersistentKeepalive":
                            persistentKeepalive = Integer.parseInt(value);
                            break;
                    }
                }
            }

            // Save last peer if exists
            if (currentSection != null && currentSection.equals("Peer") && publicKey != null) {
                peers.add(new WireguardConfig.WireguardPeer(
                    peerComment != null ? peerComment : "unknown",
                    publicKey,
                    allowedIPs,
                    endpoint,
                    persistentKeepalive
                ));
            }

            // Create interface config
            interfaceConfig = new WireguardConfig.WireguardInterface(
                address,
                listenPort,
                privateKeyPath,
                postUpCommands,
                postDownCommands
            );

            return new WireguardConfig(interfaceConfig, peers);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read WireGuard configuration file: " + configPath, e);
        }
    }

    /**
     * Extract private key path from PostUp command.
     * Example: "wg set %i private-key /etc/wireguard/%i.key" -> "/etc/wireguard/%i.key"
     */
    private String extractPrivateKeyPath(String postUpCommand) {
        Pattern pattern = Pattern.compile("private-key\\s+(\\S+)");
        Matcher matcher = pattern.matcher(postUpCommand);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: WireguardAdapter <path-to-wireguard-config>");
            System.exit(1);
        }

        WireguardAdapter adapter = new WireguardAdapter();
        String configPath = args[0];

        System.out.println("Reading WireGuard configuration from: " + configPath);

        WireguardConfig config = adapter.parseConfig(configPath);

        System.out.println("\n=== Interface Configuration ===");
        WireguardConfig.WireguardInterface iface = config.getInterfaceConfig();
        System.out.println("Address: " + iface.getAddress());
        System.out.println("ListenPort: " + iface.getListenPort());
        System.out.println("PrivateKeyPath: " + iface.getPrivateKeyPath());
        System.out.println("PostUp Commands: " + iface.getPostUpCommands().size());
        System.out.println("PostDown Commands: " + iface.getPostDownCommands().size());

        System.out.println("\n=== Peers (" + config.getPeers().size() + ") ===");
        for (WireguardConfig.WireguardPeer peer : config.getPeers()) {
            System.out.println("\nPeer: " + peer.getName());
            System.out.println("  PublicKey: " + peer.getPublicKey());
            System.out.println("  AllowedIPs: " + peer.getAllowedIPs());
            if (peer.getEndpoint() != null) {
                System.out.println("  Endpoint: " + peer.getEndpoint());
            }
            if (peer.getPersistentKeepalive() != null) {
                System.out.println("  PersistentKeepalive: " + peer.getPersistentKeepalive());
            }
        }
    }
}
