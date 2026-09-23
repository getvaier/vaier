package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.FirstRunPassword;
import net.vaier.domain.port.ForReadingFirstRunPassword;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code ${VAIER_CONFIG_PATH}/first-run-password}: two lines, the email then the secret, written
 * by dex-init (see docker-compose.yml) only while no identity provider is configured, and removed by it
 * the moment one is.
 */
@Component
@Slf4j
public class FirstRunPasswordFileAdapter implements ForReadingFirstRunPassword {

    static final String FILE_NAME = "first-run-password";

    private final Path file;

    public FirstRunPasswordFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public FirstRunPasswordFileAdapter(String configDir) {
        this.file = Path.of(configDir, FILE_NAME);
    }

    @Override
    public Optional<FirstRunPassword> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2 || lines.get(0).isBlank() || lines.get(1).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new FirstRunPassword(lines.get(0).trim(), lines.get(1).trim()));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
