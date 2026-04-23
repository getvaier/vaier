package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapCredentialsFileAdapterTest {

    @Test
    void writesPasswordFileWithOwnerOnlyPermissions(@TempDir Path tmp) throws IOException {
        BootstrapCredentialsFileAdapter adapter = new BootstrapCredentialsFileAdapter(tmp);

        String path = adapter.writeBootstrapPassword("admin", "supersecret");

        Path written = Path.of(path);
        assertThat(written).exists();
        assertThat(Files.readString(written))
            .contains("username=admin")
            .contains("password=supersecret");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(written);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void createsParentDirectoryIfMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("nested/config");
        BootstrapCredentialsFileAdapter adapter = new BootstrapCredentialsFileAdapter(nested);

        adapter.writeBootstrapPassword("admin", "x");

        assertThat(nested).exists();
        assertThat(nested.resolve(BootstrapCredentialsFileAdapter.BOOTSTRAP_FILE_NAME)).exists();
    }
}
