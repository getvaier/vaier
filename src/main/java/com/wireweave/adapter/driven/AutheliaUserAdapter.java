package com.wireweave.adapter.driven;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import com.wireweave.domain.port.ForRestartingContainers;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import lombok.extern.slf4j.Slf4j;
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

@Component
@Slf4j
public class AutheliaUserAdapter implements ForPersistingUsers {

    private final Yaml yaml;
    private final Yaml dumper;
    private final Argon2 argon2;
    private static final String AUTHELIA_USERS_DB_PATH = System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config") + "/users_database.yml";

    public AutheliaUserAdapter() {
        this.yaml = new Yaml();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);

        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    }

    @Override
    public List<User> getUsers() {
        List<User> users = new ArrayList<>();
        File usersDbFile = new File(AUTHELIA_USERS_DB_PATH);

        if (!usersDbFile.exists()) {
            log.warn("Authelia users database file not found: {}", usersDbFile.getAbsolutePath());
            return users;
        }

        try (FileInputStream inputStream = new FileInputStream(usersDbFile)) {
            Map<String, Object> config = yaml.load(inputStream);

            if (config == null) {
                return users;
            }

            // Extract users from users section
            Map<String, Object> usersMap = getNestedMap(config, "users");
            if (usersMap != null) {
                for (String username : usersMap.keySet()) {
                    users.add(new User(username));
                }
            }

            log.info("Loaded {} users from Authelia users database", users.size());

        } catch (IOException e) {
            log.error("Failed to read Authelia users database file: " + usersDbFile, e);
        }

        return users;
    }

    @Override
    public void addUser(String username, String password, String email, String displayname) {
        File usersDbFile = new File(AUTHELIA_USERS_DB_PATH);
        Map<String, Object> config;

        // Load existing config or create new one
        if (usersDbFile.exists()) {
            try (FileInputStream inputStream = new FileInputStream(usersDbFile)) {
                config = yaml.load(inputStream);
                if (config == null) {
                    config = new LinkedHashMap<>();
                }
            } catch (IOException e) {
                log.error("Failed to read Authelia users database file: " + usersDbFile, e);
                throw new RuntimeException("Failed to read users database", e);
            }
        } else {
            config = new LinkedHashMap<>();
            // Create parent directories if they don't exist
            File parentDir = usersDbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
        }

        // Get or create users map
        @SuppressWarnings("unchecked")
        Map<String, Object> usersMap = (Map<String, Object>) config.get("users");
        if (usersMap == null) {
            usersMap = new LinkedHashMap<>();
            config.put("users", usersMap);
        }

        // Check if user already exists
        if (usersMap.containsKey(username)) {
            throw new RuntimeException("User already exists: " + username);
        }

        // Hash password with Argon2id
        // Using parameters matching Authelia's defaults: m=65536, t=3, p=4
        String hashedPassword = argon2.hash(3, 65536, 4, password.toCharArray());

        // Create user entry
        Map<String, Object> userEntry = new LinkedHashMap<>();
        userEntry.put("password", hashedPassword);
        userEntry.put("displayname", displayname != null ? displayname : username);
        userEntry.put("email", email);

        // Add user to admins group by default
        List<String> groups = new ArrayList<>();
        groups.add("admins");
        userEntry.put("groups", groups);

        usersMap.put(username, userEntry);

        // Save config
        try (FileWriter writer = new FileWriter(usersDbFile)) {
            dumper.dump(config, writer);
            log.info("Added user '{}' to Authelia users database", username);
        } catch (IOException e) {
            log.error("Failed to write Authelia users database file: " + usersDbFile, e);
            throw new RuntimeException("Failed to write users database", e);
        } finally {
            // Clear password from memory
            argon2.wipeArray(password.toCharArray());
        }

    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    public static void main(String[] args) {
        ForRestartingContainers containerRestarter = new DockerContainerAdapter();
        AutheliaUserAdapter adapter = new AutheliaUserAdapter();

        List<User> users = adapter.getUsers();

        System.out.println("\nFound " + users.size() + " users:");
        users.forEach(user -> {
            System.out.println("  - " + user.getName());
        });
    }
}
