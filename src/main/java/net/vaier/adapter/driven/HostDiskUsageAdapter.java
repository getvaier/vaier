package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DiskUsage;
import net.vaier.domain.port.ForReadingDiskUsage;
import org.springframework.stereotype.Component;

/**
 * Reads free space on the host root filesystem, which is bind-mounted read-only into the Vaier
 * container at {@code VAIER_HOST_ROOT_PATH} (default {@code /host}). Pure translation: it reads
 * the {@link FileStore} capacities and hands them to the {@link DiskUsage} domain entity, which
 * owns every decision about what "full" means.
 */
@Component
@Slf4j
public class HostDiskUsageAdapter implements ForReadingDiskUsage {

    private final String hostRootPath;

    public HostDiskUsageAdapter() {
        this(System.getenv().getOrDefault("VAIER_HOST_ROOT_PATH", "/host"));
    }

    public HostDiskUsageAdapter(String hostRootPath) {
        this.hostRootPath = hostRootPath;
    }

    @Override
    public DiskUsage readHostDiskUsage() {
        Path path = Paths.get(hostRootPath);
        try {
            FileStore store = Files.getFileStore(path);
            return new DiskUsage(hostRootPath, store.getTotalSpace(), store.getUsableSpace());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read disk usage at " + hostRootPath, e);
        }
    }
}
