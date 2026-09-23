package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ServiceCredential;
import net.vaier.domain.ServiceCredentials;
import net.vaier.domain.port.ForPersistingServiceCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code service-credentials.yml}: each published service's credentials, passwords sealed by
 * {@link SecretCipher}. Read and decrypted once, then served from memory — forward auth asks on every request,
 * and Vaier is the file's only writer, so every update replaces the copy it holds.
 *
 * <pre>
 * services:
 *   openhab.example.com:
 *     shared: {username: house, password: enc:v1:…}
 *     people:
 *       turid@example.com: {username: turid, password: enc:v1:…}
 * </pre>
 */
@Component
@Slf4j
public class ServiceCredentialFileAdapter implements ForPersistingServiceCredentials {

    private static final String FILE_NAME = "service-credentials.yml";

    private final String filePath;
    private final SecretCipher cipher;
    private volatile ServiceCredentials current;

    @Autowired
    public ServiceCredentialFileAdapter(SecretCipher cipher) {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"), cipher);
    }

    public ServiceCredentialFileAdapter(String configDir, SecretCipher cipher) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public ServiceCredentials read() {
        ServiceCredentials loaded = current;
        if (loaded == null) {
            synchronized (this) {
                if (current == null) {
                    current = load();
                }
                loaded = current;
            }
        }
        return loaded;
    }

    @Override
    public synchronized void update(UnaryOperator<ServiceCredentials> change) {
        ServiceCredentials next = change.apply(read());
        write(next);
        current = next;
    }

    private ServiceCredentials load() {
        File file = new File(filePath);
        if (!file.exists()) {
            return ServiceCredentials.empty();
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Object root = new Yaml().load(in);
            Object services = root instanceof Map<?, ?> m ? m.get("services") : null;
            if (!(services instanceof Map<?, ?> byHost)) {
                return ServiceCredentials.empty();
            }
            Map<String, ServiceCredentials.Entry> entries = new LinkedHashMap<>();
            byHost.forEach((host, body) -> {
                if (body instanceof Map<?, ?> service) {
                    entries.put(String.valueOf(host), entry(String.valueOf(host), service));
                }
            });
            return ServiceCredentials.of(entries);
        } catch (IOException | RuntimeException e) {
            log.error("Could not read {}; no service credential is handed on until it is fixed", filePath, e);
            return ServiceCredentials.empty();
        }
    }

    private ServiceCredentials.Entry entry(String host, Map<?, ?> service) {
        Map<String, ServiceCredential> personal = new LinkedHashMap<>();
        if (service.get("people") instanceof Map<?, ?> people) {
            people.forEach((email, credential) -> {
                ServiceCredential read = credential(host, credential);
                if (read != null) {
                    personal.put(String.valueOf(email), read);
                }
            });
        }
        return new ServiceCredentials.Entry(credential(host, service.get("shared")), personal);
    }

    /** One unreadable credential is skipped, so it cannot take the others down with it. */
    private ServiceCredential credential(String host, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        try {
            return new ServiceCredential(String.valueOf(m.get("username")),
                cipher.decrypt(String.valueOf(m.get("password"))));
        } catch (RuntimeException e) {
            log.error("Skipping an unreadable service credential for {} in {}: {}", host, FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void write(ServiceCredentials credentials) {
        Map<String, Object> services = new LinkedHashMap<>();
        credentials.getByService().forEach((host, entry) -> {
            Map<String, Object> service = new LinkedHashMap<>();
            if (entry.shared() != null) {
                service.put("shared", sealed(entry.shared()));
            }
            if (!entry.personal().isEmpty()) {
                Map<String, Object> people = new LinkedHashMap<>();
                entry.personal().forEach((email, credential) -> people.put(email, sealed(credential)));
                service.put("people", people);
            }
            services.put(host, service);
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("services", services);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save service credentials to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private Map<String, Object> sealed(ServiceCredential credential) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", credential.getUsername());
        body.put("password", cipher.encrypt(credential.getPassword()));
        return body;
    }
}
