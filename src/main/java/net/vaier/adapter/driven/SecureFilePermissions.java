package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SecureFilePermissions {

    private SecureFilePermissions() {}

    static void lockDownFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows dev environments). No-op.
        } catch (IOException e) {
            log.warn("Failed to tighten file permissions on {}", path, e);
        }
    }

    static void lockDownDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem.
        } catch (IOException e) {
            log.warn("Failed to tighten directory permissions on {}", path, e);
        }
    }
}
