package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.port.ForPersistingHostCredentials;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed credential vault: persists one {@link HostCredential} per machine to
 * {@code host-credentials.yml}, keyed on the machine's {@code id}. The {@code secret} and {@code passphrase}
 * are encrypted at rest via {@link SecretCipher}; the machine id, username, auth method and
 * {@code managed} flag are stored in the clear.
 */
@Component
@Slf4j
public class HostCredentialFileAdapter implements ForPersistingHostCredentials {

    private static final String FILE_NAME = "host-credentials.yml";
    private final String filePath;
    private final SecretCipher cipher;

    public HostCredentialFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"), new SecretCipher());
    }

    public HostCredentialFileAdapter(String configDir, SecretCipher cipher) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public synchronized List<HostCredential> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawCredentials = data.get("credentials");
            if (!(rawCredentials instanceof List<?> list)) return List.of();
            List<HostCredential> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    HostCredential credential = deserialize(m);
                    if (credential != null) result.add(credential);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load host credentials from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<HostCredential> getByMachine(net.vaier.domain.MachineId machineId) {
        return getAll().stream().filter(c -> c.machineId().equals(machineId)).findFirst();
    }

    @Override
    public synchronized void save(HostCredential credential) {
        List<HostCredential> current = new ArrayList<>(getAll());
        current.removeIf(c -> c.machineId().equals(credential.machineId()));
        current.add(credential);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByMachine(net.vaier.domain.MachineId machineId) {
        List<HostCredential> current = new ArrayList<>(getAll());
        boolean removed = current.removeIf(c -> c.machineId().equals(machineId));
        if (removed) writeAll(current);
    }

    private HostCredential deserialize(Map<?, ?> m) {
        String rawId = asString(m.get("machineId"));
        String username = asString(m.get("username"));
        AuthMethod authMethod = parseAuthMethod(asString(m.get("authMethod")));
        String secret = cipher.decrypt(asString(m.get("secret")));
        String passphrase = cipher.decrypt(asString(m.get("passphrase")));
        boolean managed = m.get("managed") instanceof Boolean b && b;
        if (rawId == null || username == null || authMethod == null || secret == null) {
            log.warn("Skipping malformed host-credential entry in {}", filePath);
            return null;
        }
        net.vaier.domain.MachineId machineId;
        try {
            machineId = net.vaier.domain.MachineId.of(rawId);
        } catch (IllegalArgumentException e) {
            // A credential whose machineId is unreadable belongs to no machine Vaier can name. Skipping
            // it loudly beats guessing: the wrong guess hands a login to the wrong host.
            log.error("Skipping host-credential entry in {} with an unusable machineId: {}",
                filePath, e.getMessage());
            return null;
        }
        return new HostCredential(machineId, username, authMethod, secret, passphrase, managed);
    }

    private void writeAll(List<HostCredential> credentials) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (HostCredential c : credentials) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", c.machineId().value());
            entry.put("username", c.username());
            entry.put("authMethod", c.authMethod().name());
            entry.put("secret", cipher.encrypt(c.secret()));
            if (c.passphrase() != null) {
                entry.put("passphrase", cipher.encrypt(c.passphrase()));
            }
            entry.put("managed", c.managed());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("credentials", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save host credentials to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private AuthMethod parseAuthMethod(String value) {
        if (value == null) return null;
        try {
            return AuthMethod.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown auth method '{}' in {}", value.replaceAll("[\r\n]+", "_"), FILE_NAME);
            return null;
        }
    }
}
