package com.wireweave.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Component
@Slf4j
public class AutheliaConfigInitializer {

    private static final String AUTHELIA_CONFIG_PATH = System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config");
    private static final String CONFIGURATION_FILE = AUTHELIA_CONFIG_PATH + "/configuration.yml";

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAutheliaConfig() {
        File configFile = new File(CONFIGURATION_FILE);

        log.info("Overwriting Authelia configuration file at: {}", configFile.getAbsolutePath());

        // Create parent directories if they don't exist
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("Created directories: {}", parentDir.getAbsolutePath());
            }
        }

        String configContent = generateDefaultConfig();

        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(configContent);
            log.info("Successfully wrote Authelia configuration file");
            restartAutheliaContainer();
        } catch (IOException e) {
            log.error("Failed to write Authelia configuration file", e);
            throw new RuntimeException("Failed to initialize Authelia configuration", e);
        }
    }

    private void restartAutheliaContainer() {
        try {
            log.info("Restarting Authelia container...");
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "restart", "authelia");
            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("Successfully restarted Authelia container");
            } else {
                log.error("Failed to restart Authelia container. Exit code: {}", exitCode);
            }
        } catch (IOException | InterruptedException e) {
            log.error("Error restarting Authelia container", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String generateDefaultConfig() {
        String domain = System.getenv().getOrDefault("WIREWEAVE_DOMAIN", "example.com");

        return String.format("""
                ###############################################################
                #                Authelia minimal configuration               #
                ###############################################################
                server:
                  address: tcp://0.0.0.0:9091
                logs_level: info
                jwt_secret: %s
                authentication_backend:
                  file:
                    path: /config/users_database.yml
                totp:
                  issuer: %s
                session:
                  secret: %s
                  domain: %s
                  expiration: 3600 # 1 hour
                  inactivity: 300 # 5 minutes
                  redis:
                    host: redis
                    port: 6379
                storage:
                  encryption_key: %s
                  local:
                    path: /config/db.sqlite
                access_control:
                  default_policy: bypass
                  rules:
                    - domain: "wireweave.%s"
                      policy: one_factor
                notifier:
                  filesystem:
                    filename: /config/emails.txt
                """,
            generateSecureSecret(32),  // jwt_secret
            domain,                     // totp issuer
            generateSecureSecret(32),  // session secret
            domain,                     // session domain
            generateSecureSecret(64),  // storage encryption_key
            domain                      // wireweave subdomain
        );
    }

    private String generateSecureSecret(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder secret = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();

        for (int i = 0; i < length; i++) {
            secret.append(chars.charAt(random.nextInt(chars.length())));
        }

        return secret.toString();
    }
}
