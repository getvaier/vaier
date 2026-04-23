package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AutheliaAssetsAdapterTest {

    @Test
    void publishesLogoIntoAssetsDirectoryUnderAutheliaConfig(@TempDir Path tmp) {
        AutheliaAssetsAdapter adapter = new AutheliaAssetsAdapter(tmp);

        adapter.publishAssets();

        assertThat(tmp.resolve("assets/logo.png")).exists().isRegularFile();
    }

    @Test
    void createsAssetsDirectoryIfMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("deep/nested/config");
        AutheliaAssetsAdapter adapter = new AutheliaAssetsAdapter(nested);

        adapter.publishAssets();

        assertThat(nested.resolve("assets")).exists().isDirectory();
        assertThat(nested.resolve("assets/logo.png")).exists();
    }

    @Test
    void writtenLogoMatchesClasspathSourceByteForByte(@TempDir Path tmp) throws IOException {
        AutheliaAssetsAdapter adapter = new AutheliaAssetsAdapter(tmp);

        adapter.publishAssets();

        byte[] written = Files.readAllBytes(tmp.resolve("assets/logo.png"));
        byte[] source;
        try (InputStream in = getClass().getResourceAsStream("/brand/logo.png")) {
            source = in.readAllBytes();
        }
        assertThat(written).isEqualTo(source);
    }

    @Test
    void overwritesExistingLogoOnRepeatedCalls(@TempDir Path tmp) throws IOException {
        Path assetsDir = tmp.resolve("assets");
        Files.createDirectories(assetsDir);
        Files.writeString(assetsDir.resolve("logo.png"), "stale content");

        AutheliaAssetsAdapter adapter = new AutheliaAssetsAdapter(tmp);
        adapter.publishAssets();

        byte[] written = Files.readAllBytes(assetsDir.resolve("logo.png"));
        byte[] source;
        try (InputStream in = getClass().getResourceAsStream("/brand/logo.png")) {
            source = in.readAllBytes();
        }
        assertThat(written).isEqualTo(source);
    }
}
