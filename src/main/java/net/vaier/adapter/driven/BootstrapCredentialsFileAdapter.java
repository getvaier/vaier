package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForWritingBootstrapCredentials;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class BootstrapCredentialsFileAdapter implements ForWritingBootstrapCredentials {

    static final String BOOTSTRAP_FILE_NAME = ".bootstrap-admin-password";

    private final Path bootstrapDir;

    public BootstrapCredentialsFileAdapter() {
        this(Path.of(System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config")));
    }

    BootstrapCredentialsFileAdapter(Path bootstrapDir) {
        this.bootstrapDir = bootstrapDir;
    }

    @Override
    public String writeBootstrapPassword(String username, String password) {
        Path target = bootstrapDir.resolve(BOOTSTRAP_FILE_NAME);
        try {
            Files.createDirectories(bootstrapDir);
            String body = String.format("""
                    # Vaier bootstrap admin credentials.
                    # Read these, log in, change the password, then delete this file.
                    username=%s
                    password=%s
                    """, username, password);
            Files.writeString(target, body);
            SecureFilePermissions.lockDownFile(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write bootstrap password file: " + target, e);
        }
        return target.toAbsolutePath().toString();
    }
}
