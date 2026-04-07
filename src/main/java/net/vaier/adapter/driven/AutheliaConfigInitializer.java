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

    private final String configurationFile;
    private final String secretsFile;
    private final String vaierDomain;

    public AutheliaConfigInitializer() {
        this(
            System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config"),
            System.getenv().getOrDefault("VAIER_DOMAIN", "")
        );
    }

    AutheliaConfigInitializer(String configPath, String vaierDomain) {
        this.configurationFile = configPath + "/configuration.yml";
        this.secretsFile = configPath + "/secrets.properties";
        this.vaierDomain = vaierDomain;
    }

    @Override
    public boolean initialiseConfiguration() {
        File configFile = new File(configurationFile);

        // Create parent directories if they don't exist
        File parentDir = configFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("Created directories: {}", parentDir.getAbsolutePath());
            }
        }

        String configContent = generateDefaultConfig();

        if (configFile.exists()) {
            try {
                String existing = java.nio.file.Files.readString(configFile.toPath());
                if (existing.equals(configContent)) {
                    log.info("Authelia configuration unchanged, skipping write");
                    return false;
                }
            } catch (IOException e) {
                log.warn("Could not read existing Authelia configuration, will overwrite", e);
            }
        }

        log.info("Writing Authelia configuration file at: {}", configFile.getAbsolutePath());
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(configContent);
            log.info("Successfully wrote Authelia configuration file");
        } catch (IOException e) {
            log.error("Failed to write Authelia configuration file", e);
            throw new RuntimeException("Failed to initialize Authelia configuration", e);
        }
        return true;
    }

    private String generateDefaultConfig() {
        String vaierFullDomain = "vaier." + vaierDomain;
        // Extract base domain from vaier domain (e.g., "vaier.eilertsen.family" -> "eilertsen.family")
        String baseDomain = vaierFullDomain.contains(".")
            ? vaierFullDomain.substring(vaierFullDomain.indexOf('.') + 1)
            : vaierFullDomain;

        String regexBaseDomain = baseDomain.replace(".", "\\.");

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
                log:
                  level: info
                identity_validation:
                  reset_password:
                    jwt_secret: %s
                authentication_backend:
                  file:
                    path: /config/users_database.yml
                totp:
                  issuer: %s
                session:
                  secret: %s
                  cookies:
                    - domain: %s
                      authelia_url: https://%s
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
                  default_policy: one_factor
                  rules:
                    - domain_regex: '^.*\\.%s$'
                      policy: bypass
                      resources:
                        - '^/favicon\\.ico$'
                        - '^/favicon\\.png$'
                        - '^/apple-touch-icon\\.png$'
                        - '^/apple-touch-icon-precomposed\\.png$'
                    - domain: "%s"
                      policy: bypass
                      resources:
                        - "^/$"
                        - "^/launchpad.html$"
                        - "^/hosted-services/discover$"
                        - "^/favicon$"
                    - domain: "%s"
                      policy: one_factor
                notifier:
                  filesystem:
                    filename: /config/emails.txt
                """,
            jwtSecret,                  // jwt_secret
            baseDomain,                 // totp issuer
            sessionSecret,              // session secret
            baseDomain,                 // session cookie domain
            vaierFullDomain,            // authelia_url
            encryptionKey,              // storage encryption_key
            regexBaseDomain,            // domain_regex favicon bypass (dots escaped)
            vaierFullDomain,            // vaier full domain for bypass rule
            vaierFullDomain             // vaier full domain for one_factor rule
        );
    }

    private Properties loadOrGenerateSecrets() {
        File secretsFile = new File(this.secretsFile);
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
