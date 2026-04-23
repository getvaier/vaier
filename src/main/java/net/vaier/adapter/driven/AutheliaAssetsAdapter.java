package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPublishingAutheliaAssets;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AutheliaAssetsAdapter implements ForPublishingAutheliaAssets {

    private static final String LOGO_RESOURCE = "/brand/logo.png";
    private static final String LOGO_FILE_NAME = "logo.png";

    private final Path autheliaConfigDir;

    public AutheliaAssetsAdapter() {
        this(Path.of(System.getenv().getOrDefault("AUTHELIA_CONFIG_PATH", "./authelia/config")));
    }

    AutheliaAssetsAdapter(Path autheliaConfigDir) {
        this.autheliaConfigDir = autheliaConfigDir;
    }

    @Override
    public void publishAssets() {
        Path assetsDir = autheliaConfigDir.resolve("assets");
        Path target = assetsDir.resolve(LOGO_FILE_NAME);
        try {
            Files.createDirectories(assetsDir);
            try (InputStream in = getClass().getResourceAsStream(LOGO_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Logo resource missing from classpath: " + LOGO_RESOURCE);
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Published Authelia branding asset: {}", target.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to publish Authelia assets to " + assetsDir, e);
        }
    }
}
