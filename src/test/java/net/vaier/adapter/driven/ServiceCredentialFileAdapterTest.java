package net.vaier.adapter.driven;

import net.vaier.domain.AccessEntry;
import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.Role;
import net.vaier.domain.ServiceCredential;
import net.vaier.domain.ServiceCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCredentialFileAdapterTest {

    @TempDir
    Path tempDir;

    private static final ReverseProxyRoute OPENHAB = ReverseProxyRoute.builder().name("openhab-router")
        .domainName("openhab.example.com").middlewares(AuthMode.SOCIAL.authMiddlewareNames()).build();

    private static ServiceCredentials openhabWithBoth() {
        return ServiceCredentials.empty()
            .withShared("openhab.example.com", new ServiceCredential("house", "shared-secret-pw"), List.of(OPENHAB))
            .withPersonal("openhab.example.com", "turid@example.com", new ServiceCredential("turid", "turids-secret-pw"),
                List.of(OPENHAB), List.of(AccessEntry.builder().email("turid@example.com").role(Role.USER).build()));
    }

    private ServiceCredentialFileAdapter adapter() {
        return new ServiceCredentialFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
    }

    @Test
    void roundTripsThroughAFileThatHoldsNoPasswordInTheClear() throws Exception {
        adapter().update(before -> openhabWithBoth());

        Path file = tempDir.resolve("service-credentials.yml");
        assertThat(Files.readString(file))
            .doesNotContain("shared-secret-pw").doesNotContain("turids-secret-pw")
            .contains("enc:v1:").contains("openhab.example.com").contains("turid@example.com").contains("house");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(adapter().read()).isEqualTo(openhabWithBoth());
    }

    @Test
    void readsTheFileOnce_andAnUpdateIsWhatChangesTheAnswer() throws Exception {
        ServiceCredentialFileAdapter adapter = adapter();
        assertThat(adapter.read().getByService()).isEmpty();

        adapter.update(before -> openhabWithBoth());
        Files.delete(tempDir.resolve("service-credentials.yml"));

        // Forward auth asks on every request, so the answer comes from memory, never from the disk.
        assertThat(adapter.read()).isEqualTo(openhabWithBoth());
    }
}
