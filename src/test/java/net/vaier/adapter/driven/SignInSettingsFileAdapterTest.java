package net.vaier.adapter.driven;

import net.vaier.domain.ProviderCredentials;
import net.vaier.domain.SignInSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;

import static net.vaier.domain.IdentityProvider.GITHUB;
import static org.assertj.core.api.Assertions.assertThat;

/** The file is the contract with dex-init and oauth2-proxy-init, which parse it line by line with sed. */
class SignInSettingsFileAdapterTest {

    @Test
    void savesWhatTheRenderersParse_ownerOnly_andReadsItBack(@TempDir Path dir) throws Exception {
        assertThat(new SignInSettingsFileAdapter(dir.toString()).read()).as("absent").isEqualTo(SignInSettings.none());

        SignInSettings settings = new SignInSettings(Map.of(GITHUB, new ProviderCredentials("Ov23li", "abc123")), true);
        new SignInSettingsFileAdapter(dir.toString()).save(settings);

        Path file = dir.resolve("sign-in-providers.env");
        assertThat(Files.readAllLines(file))
            .contains("FIRST_RUN_DOOR=open", "GITHUB_CLIENT_ID=Ov23li", "GITHUB_CLIENT_SECRET=abc123")
            .noneMatch(line -> line.startsWith("GOOGLE_"));
        assertThat(Files.getPosixFilePermissions(file))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(new SignInSettingsFileAdapter(dir.toString()).read()).isEqualTo(settings);
    }
}
