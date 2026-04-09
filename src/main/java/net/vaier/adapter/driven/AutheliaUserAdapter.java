package net.vaier.adapter.driven;

import net.vaier.domain.User;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
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
    private final String usersDbPath;

    public AutheliaUserAdapter() {
        this(System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config") + "/users_database.yml");
    }

    AutheliaUserAdapter(String usersDbPath) {
        this.usersDbPath = usersDbPath;
        this.yaml = new Yaml();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);

        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
    }

    @Override
    public boolean isDatabaseInitialised() {
        return new File(usersDbPath).exists();
    }

    @Override
    public List<User> getUsers() {
        List<User> users = new ArrayList<>();
        File usersDbFile = new File(usersDbPath);

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
                for (Map.Entry<String, Object> entry : usersMap.entrySet()) {
                    String username = entry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userEntry = (Map<String, Object>) entry.getValue();
                    String displayname = userEntry != null ? (String) userEntry.get("displayname") : null;
                    String email = userEntry != null ? (String) userEntry.get("email") : null;
                    @SuppressWarnings("unchecked")
                    List<String> groups = userEntry != null ? (List<String>) userEntry.get("groups") : null;
                    users.add(new User(username, displayname, email, groups != null ? groups : List.of()));
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
        File usersDbFile = new File(usersDbPath);
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

    @Override
    public void deleteUser(String username) {
        File usersDbFile = new File(usersDbPath);

        if (!usersDbFile.exists()) {
            throw new RuntimeException("Users database file not found: " + usersDbFile.getAbsolutePath());
        }

        Map<String, Object> config;
        try (FileInputStream inputStream = new FileInputStream(usersDbFile)) {
            config = yaml.load(inputStream);
            if (config == null) {
                throw new RuntimeException("Users database is empty");
            }
        } catch (IOException e) {
            log.error("Failed to read Authelia users database file: " + usersDbFile, e);
            throw new RuntimeException("Failed to read users database", e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> usersMap = (Map<String, Object>) config.get("users");
        if (usersMap == null || !usersMap.containsKey(username)) {
            throw new RuntimeException("User not found: " + username);
        }

        usersMap.remove(username);

        // Save config
        try (FileWriter writer = new FileWriter(usersDbFile)) {
            dumper.dump(config, writer);
            log.info("Deleted user '{}' from Authelia users database", username);
        } catch (IOException e) {
            log.error("Failed to write Authelia users database file: " + usersDbFile, e);
            throw new RuntimeException("Failed to write users database", e);
        }
    }

    @Override
    public void changePassword(String username, String newPassword) {
        File usersDbFile = new File(usersDbPath);

        if (!usersDbFile.exists()) {
            throw new RuntimeException("Users database file not found: " + usersDbFile.getAbsolutePath());
        }

        Map<String, Object> config;
        try (FileInputStream inputStream = new FileInputStream(usersDbFile)) {
            config = yaml.load(inputStream);
            if (config == null) {
                throw new RuntimeException("Users database is empty");
            }
        } catch (IOException e) {
            log.error("Failed to read Authelia users database file: " + usersDbFile, e);
            throw new RuntimeException("Failed to read users database", e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> usersMap = (Map<String, Object>) config.get("users");
        if (usersMap == null || !usersMap.containsKey(username)) {
            throw new RuntimeException("User not found: " + username);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> userEntry = (Map<String, Object>) usersMap.get(username);

        // Hash password with Argon2id
        String hashedPassword = argon2.hash(3, 65536, 4, newPassword.toCharArray());
        userEntry.put("password", hashedPassword);

        // Save config
        try (FileWriter writer = new FileWriter(usersDbFile)) {
            dumper.dump(config, writer);
            log.info("Changed password for user '{}' in Authelia users database", username);
        } catch (IOException e) {
            log.error("Failed to write Authelia users database file: " + usersDbFile, e);
            throw new RuntimeException("Failed to write users database", e);
        } finally {
            // Clear password from memory
            argon2.wipeArray(newPassword.toCharArray());
        }
    }

}
