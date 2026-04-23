package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SecureFilePermissionsTest {

    @Test
    void setsFilePermissionsToOwnerOnly(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("secret.txt"), "data");

        SecureFilePermissions.lockDownFile(file);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void setsDirectoryPermissionsToOwnerOnly(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("secrets"));

        SecureFilePermissions.lockDownDirectory(dir);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    }
}
