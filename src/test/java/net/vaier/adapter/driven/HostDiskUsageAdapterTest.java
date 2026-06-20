package net.vaier.adapter.driven;

import net.vaier.domain.DiskUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HostDiskUsageAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void readHostDiskUsage_reportsThePathItWasConfiguredWith() {
        HostDiskUsageAdapter adapter = new HostDiskUsageAdapter(tempDir.toString());

        DiskUsage usage = adapter.readHostDiskUsage();

        assertThat(usage.path()).isEqualTo(tempDir.toString());
    }

    @Test
    void readHostDiskUsage_reportsRealFileStoreCapacities() {
        HostDiskUsageAdapter adapter = new HostDiskUsageAdapter(tempDir.toString());

        DiskUsage usage = adapter.readHostDiskUsage();

        // The temp dir lives on a real filesystem, so it must report a positive total
        // and a usable amount that never exceeds the total.
        assertThat(usage.totalBytes()).isPositive();
        assertThat(usage.usableBytes()).isLessThanOrEqualTo(usage.totalBytes());
        assertThat(usage.usedPercent()).isBetween(0, 100);
    }
}
