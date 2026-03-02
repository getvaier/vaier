package net.vaier.adapter.driven;

import net.vaier.domain.port.ForInitialisingUserService;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AutheliaConfigInitializer implements ForInitialisingUserService {

    private static final String AUTHELIA_CONFIG_PATH = System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config");
    private static final String CONFIGURATION_FILE = AUTHELIA_CONFIG_PATH + "/configuration.yml";
    private static final String SECRETS_FILE = AUTHELIA_CONFIG_PATH + "/secrets.properties";

    @Override
    public void initialiseConfiguration() {
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
        } catch (IOException e) {
            log.error("Failed to write Authelia configuration file", e);
            throw new RuntimeException("Failed to initialize Authelia configuration", e);
        }
    }

    private String generateDefaultConfig() {
        String vaierFullDomain = "vaier." + System.getenv().get("VAIER_DOMAIN");
        // Extract base domain from vaier domain (e.g., "vaier.eilertsen.family" -> "eilertsen.family")
        String baseDomain = vaierFullDomain.contains(".")
            ? vaierFullDomain.substring(vaierFullDomain.indexOf('.') + 1)
            : vaierFullDomain;

        // Load or generate secrets
        Properties secrets = loadOrGenerateSecrets();
        String jwtSecret = secrets.getProperty("jwt_secret");
        String sessionSecret = secrets.getProperty("session_secret");
        String encryptionKey = secrets.getProperty("encryption_key");

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
                  domain: .%s
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
                    - domain: "%s"
                      policy: one_factor
                notifier:
                  filesystem:
                    filename: /config/emails.txt
                """,
            jwtSecret,                  // jwt_secret
            baseDomain,                 // totp issuer
            sessionSecret,              // session secret
            baseDomain,                 // session domain
            encryptionKey,              // storage encryption_key
            vaierFullDomain         // vaier full domain for access control
        );
    }

    private Properties loadOrGenerateSecrets() {
        File secretsFile = new File(SECRETS_FILE);
        Properties secrets = new Properties();

        if (secretsFile.exists()) {
            try (FileInputStream fis = new FileInputStream(secretsFile)) {
                secrets.load(fis);
                log.info("Loaded existing secrets from: {}", secretsFile.getAbsolutePath());
                return secrets;
            } catch (IOException e) {
                log.warn("Failed to load secrets file, generating new secrets", e);
            }
        }

        // Generate new secrets
        log.info("Generating new secrets and saving to: {}", secretsFile.getAbsolutePath());
        secrets.setProperty("jwt_secret", generateSecureSecret(32));
        secrets.setProperty("session_secret", generateSecureSecret(32));
        secrets.setProperty("encryption_key", generateSecureSecret(64));

        // Save secrets
        try (FileOutputStream fos = new FileOutputStream(secretsFile)) {
            secrets.store(fos, "Authelia secrets - DO NOT MODIFY OR DELETE");
            log.info("Successfully saved secrets to file");
        } catch (IOException e) {
            log.error("Failed to save secrets file", e);
            throw new RuntimeException("Failed to save secrets", e);
        }

        return secrets;
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
