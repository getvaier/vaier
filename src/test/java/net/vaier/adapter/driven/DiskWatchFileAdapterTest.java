package net.vaier.adapter.driven;

import net.vaier.domain.DiskWatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiskWatchFileAdapterTest {

    @TempDir
    Path configDir;

    private DiskWatchFileAdapter adapter() {
        return new DiskWatchFileAdapter(configDir.toString());
    }

    @Test
    void withNoFileYet_thereAreNoWatches_andEverythingIsWatchedByDefault() {
        // No file is the normal state on first boot, and it must not be an error: every filesystem is
        // watched at the global threshold until someone says otherwise.
        assertThat(adapter().getAll()).isEmpty();
    }

    @Test
    void aWatchSurvivesARestart() {
        adapter().save(new DiskWatch("NAS", "/", true, 95));

        assertThat(adapter().getAll())
            .containsExactly(new DiskWatch("NAS", "/", true, 95));
    }

    @Test
    void savingTheSameFilesystemTwice_replacesIt_ratherThanDuplicatingIt() {
        DiskWatchFileAdapter adapter = adapter();
        adapter.save(new DiskWatch("NAS", "/", true, 95));
        adapter.save(new DiskWatch("NAS", "/", false, null));

        assertThat(adapter.getAll()).containsExactly(new DiskWatch("NAS", "/", false, null));
    }

    @Test
    void watchesOnTheSameMountOfDifferentMachines_areDifferentWatches() {
        DiskWatchFileAdapter adapter = adapter();
        adapter.save(new DiskWatch("NAS", "/", false, null));
        adapter.save(new DiskWatch("Apalveien 5", "/", true, 70));

        assertThat(adapter.getAll()).hasSize(2);
    }

    @Test
    void aMalformedEntry_isSkipped_andTheGoodOnesStillLoad() throws IOException {
        // Same tolerance as the backup file adapters: a bad entry is skipped with a warning, never a crash
        // that takes every other watch down with it. A watch whose machine or mount no longer exists is
        // simply never looked up — it is not an error.
        Files.writeString(configDir.resolve("disk-watches.yml"),
            "watches:\n"
                + "- machineName: NAS\n"
                + "  mountPoint: /volume1\n"
                + "  watched: true\n"
                + "  thresholdPercent: 90\n"
                + "- mountPoint: /orphan\n"                 // no machine name
                + "  watched: false\n"
                + "- machineName: Ghost\n"                  // no mount point
                + "  watched: false\n"
                + "- machineName: NAS\n"
                + "  mountPoint: /bad\n"
                + "  watched: true\n"
                + "  thresholdPercent: 9000\n");            // out of range

        assertThat(adapter().getAll())
            .containsExactly(new DiskWatch("NAS", "/volume1", true, 90));
    }

    @Test
    void unreadableGarbage_loadsAsNothing_ratherThanBlowingUp() throws IOException {
        Files.writeString(configDir.resolve("disk-watches.yml"), "\t: not: yaml: [");

        assertThat(adapter().getAll()).isEmpty();
    }
}
