package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.AccessRoster;
import net.vaier.domain.Role;
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
 *     role: admin            # admin | user | pending — sole authority for admin-vs-user
 *     groups: [devs]         # per-service access tags only (never admins/users)
 * serviceGroups:
 *   plex.example.com: family
 *   git.example.com: devs
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
    private static final String NAME_KEY = "name";

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

        ensureConfiguredAdminExists(adminEmail);
    }

    /**
     * Guarantee the access store always holds at least one admin, so the admin-only console can never
     * lock everyone out (there is no Authelia fallback once it is decommissioned). Idempotent and
     * self-healing:
     *
     * <ul>
     *   <li>If any entry is already an admin, do nothing.</li>
     *   <li>Otherwise, if {@code VAIER_ADMIN_EMAIL} is set: promote that identity to {@code
     *       role=admin} — preserving its existing groups and name if it already exists as a
     *       non-admin, or creating it with empty groups if it doesn't. The Role alone is the
     *       authority for admin-vs-user, so no group is needed; groups stay purely per-service.</li>
     *   <li>Otherwise (adminless and no configured email) log a warning and leave the store as-is —
     *       there is nothing safe to heal with.</li>
     * </ul>
     */
    private synchronized void ensureConfiguredAdminExists(String adminEmail) {
        if (new AccessRoster(getEntries()).adminCount() > 0) {
            return;
        }
        if (adminEmail == null || adminEmail.isBlank()) {
            log.warn("No administrator in {} and VAIER_ADMIN_EMAIL is unset — the console may be "
                    + "locked out. Set VAIER_ADMIN_EMAIL so an admin can be restored.", FILE_NAME);
            return;
        }
        String normalised = adminEmail.trim().toLowerCase(java.util.Locale.ROOT);
        AccessEntry admin = findByEmail(normalised)
                .map(existing -> existing.toBuilder().role(Role.ADMIN).build())
                .orElseGet(() -> AccessEntry.builder()
                        .email(normalised).role(Role.ADMIN).groups(List.of()).build());
        log.info("Ensuring configured admin '{}' exists as ADMIN in {}", normalised, FILE_NAME);
        upsert(admin);
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
            result.add(AccessEntry.builder().email(email).role(role)
                    .groups(readGroups(body.get(GROUPS_KEY)))
                    .name(asString(body.get(NAME_KEY)))
                    .build());
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
        // Only write the display name once we actually have one — pre-approved entries stay nameless
        // in the file until their first sign-in fills it in.
        if (entry.getName() != null && !entry.getName().isBlank()) {
            body.put(NAME_KEY, entry.getName());
        }
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
