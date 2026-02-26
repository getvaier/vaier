package com.wireweave.adapter.driven;

import com.wireweave.domain.User;
import com.wireweave.domain.port.ForPersistingUsers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AutheliaUserAdapter implements ForPersistingUsers {

    private final Yaml yaml;
    private static final String AUTHELIA_USERS_DB_PATH = System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config") + "/users_database.yml";

    public AutheliaUserAdapter() {
        this.yaml = new Yaml();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    public static void main(String[] args) {
        AutheliaUserAdapter adapter = new AutheliaUserAdapter();

        List<User> users = adapter.getUsers();

        System.out.println("\nFound " + users.size() + " users:");
        users.forEach(user -> {
            System.out.println("  - " + user.getName());
        });
    }
}
