package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForResolvingServiceGroup;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File-based access store for social-login authorization — a sibling of Authelia's
 * {@code users_database.yml}. Persists the {@code email → role + groups} map and the per-host
 * required group at {@code ${VAIER_CONFIG_PATH}/access.yml}. Mirrors {@link AutheliaUserAdapter}'s
 * SnakeYAML load/dump style and locks the file down to owner-only after each write.
 *
 * <p>Schema:
 * <pre>
 * entries:
 *   you@gmail.com:
 *     role: admin            # admin | user | pending
 *     groups: [admins]
 * serviceGroups:
 *   plex.example.com: family
 *   vaier.example.com: admins
 * </pre>
 */
@Component
@Slf4j
public class AccessFileAdapter implements ForPersistingAccessEntries, ForResolvingServiceGroup {

    private static final String FILE_NAME = "access.yml";
    private static final String ENTRIES_KEY = "entries";
    private static final String SERVICE_GROUPS_KEY = "serviceGroups";
    private static final String ROLE_KEY = "role";
    private static final String GROUPS_KEY = "groups";

    private final String filePath;
    private final Yaml dumper;

    public AccessFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"),
                System.getenv("VAIER_ADMIN_EMAIL"));
    }

    public AccessFileAdapter(String configDir, String adminEmail) {
        this.filePath = configDir + "/" + FILE_NAME;

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);

        seedFirstAdminIfNeeded(adminEmail);
    }

    /**
     * With social login there is no password file, so the owner could otherwise land as pending and
     * lock themselves out. When the store is empty and {@code VAIER_ADMIN_EMAIL} is set, seed that
     * identity as an admin (in the {@code admins} group) so the first login already has access.
     */
    private synchronized void seedFirstAdminIfNeeded(String adminEmail) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }
        if (!getEntries().isEmpty()) {
            return;
        }
        log.info("Seeding first admin '{}' into {}", adminEmail, FILE_NAME);
        upsert(AccessEntry.builder()
                .email(adminEmail.trim().toLowerCase(java.util.Locale.ROOT))
                .role(Role.ADMIN)
                .groups(List.of(User.ADMINS_GROUP))
                .build());
    }

    @Override
    public synchronized List<AccessEntry> getEntries() {
        Map<String, Object> root = load();
        Object rawEntries = root.get(ENTRIES_KEY);
        if (!(rawEntries instanceof Map<?, ?> entriesMap)) {
            return List.of();
        }
        List<AccessEntry> result = new ArrayList<>();
        for (Map.Entry<?, ?> e : entriesMap.entrySet()) {
            String email = String.valueOf(e.getKey());
            Map<?, ?> body = e.getValue() instanceof Map<?, ?> m ? m : Map.of();
            Role role = Role.fromString(asString(body.get(ROLE_KEY)));
            result.add(AccessEntry.builder().email(email).role(role).groups(readGroups(body.get(GROUPS_KEY))).build());
        }
        return result;
    }

    @Override
    public synchronized Optional<AccessEntry> findByEmail(String email) {
        return getEntries().stream().filter(e -> e.getEmail().equals(email)).findFirst();
    }

    @Override
    public synchronized void upsert(AccessEntry entry) {
        Map<String, Object> root = load();
        Map<String, Object> entries = section(root, ENTRIES_KEY);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ROLE_KEY, entry.getRole().wireValue());
        body.put(GROUPS_KEY, entry.getGroups() != null ? new ArrayList<>(entry.getGroups()) : new ArrayList<>());
        entries.put(entry.getEmail(), body);
        write(root);
    }

    @Override
    public synchronized void delete(String email) {
        Map<String, Object> root = load();
        Object rawEntries = root.get(ENTRIES_KEY);
        if (rawEntries instanceof Map<?, ?> entriesMap && entriesMap.containsKey(email)) {
            ((Map<?, ?>) entriesMap).remove(email);
            write(root);
        }
    }

    @Override
    public synchronized Optional<String> requiredGroupForHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        Object rawGroups = load().get(SERVICE_GROUPS_KEY);
        if (!(rawGroups instanceof Map<?, ?> groupsMap)) {
            return Optional.empty();
        }
        String group = asString(groupsMap.get(host));
        return (group == null || group.isBlank()) ? Optional.empty() : Optional.of(group);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        File file = new File(filePath);
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Object loaded = new Yaml().load(fis);
            return loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
        } catch (IOException e) {
            log.warn("Failed to read access store from {}", filePath, e);
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> root, String key) {
        Object existing = root.get(key);
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        root.put(key, created);
        return created;
    }

    private void write(Map<String, Object> root) {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileWriter writer = new FileWriter(file)) {
            dumper.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write access store to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private static List<String> readGroups(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                groups.add(o.toString());
            }
        }
        return groups;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
