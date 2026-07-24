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

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @TempDir
    Path configDir;

    private DiskWatchFileAdapter adapter() {
        return new DiskWatchFileAdapter(configDir.toString());
    }

    private static final net.vaier.domain.MachineId NAS = mid("NAS");

    /**
     * A watch whose stored machine id is missing or unreadable is skipped <em>loudly</em>, never quietly
     * dropped. The fallback for an unconfigured filesystem is watched-at-the-global-threshold, so a watch
     * that fails to load does not go silent — it reverts to a threshold nobody chose. That is the failure
     * #325 exists to prevent, so it has to be visible in the log rather than inferred from an alert that
     * fires at the wrong number.
     */
    @Test
    void getAll_skipsAWatchWithNoMachineIdButKeepsTheRest() throws Exception {
        Files.writeString(configDir.resolve("disk-watches.yml"), """
            watches:
            - mountPoint: /
              watched: false
            - machineId: %s
              mountPoint: /volume1
              watched: false
              thresholdPercent: 95
            """.formatted(NAS.value()));

        assertThat(adapter().getAll()).singleElement()
            .satisfies(w -> {
                assertThat(w.machineId()).isEqualTo(NAS);
                assertThat(w.mountPoint()).isEqualTo("/volume1");
                assertThat(w.watched()).isFalse();
                assertThat(w.thresholdPercent()).isEqualTo(95);
            });
    }

    @Test
    void save_thenGetAll_roundTripsTheMachineId() {
        adapter().save(new DiskWatch(NAS, "/volume1", false, 95));

        assertThat(adapter().getAll()).singleElement()
            .extracting(DiskWatch::machineId).isEqualTo(NAS);
    }

    @Test
    void withNoFileYet_thereAreNoWatches_andEverythingIsWatchedByDefault() {
        // No file is the normal state on first boot, and it must not be an error: every filesystem is
        // watched at the global threshold until someone says otherwise.
        assertThat(adapter().getAll()).isEmpty();
    }

    @Test
    void aWatchSurvivesARestart() {
        adapter().save(new DiskWatch(mid("NAS"), "/", true, 95));

        assertThat(adapter().getAll())
            .containsExactly(new DiskWatch(mid("NAS"), "/", true, 95));
    }

    @Test
    void savingTheSameFilesystemTwice_replacesIt_ratherThanDuplicatingIt() {
        DiskWatchFileAdapter adapter = adapter();
        adapter.save(new DiskWatch(mid("NAS"), "/", true, 95));
        adapter.save(new DiskWatch(mid("NAS"), "/", false, null));

        assertThat(adapter.getAll()).containsExactly(new DiskWatch(mid("NAS"), "/", false, null));
    }

    @Test
    void watchesOnTheSameMountOfDifferentMachines_areDifferentWatches() {
        DiskWatchFileAdapter adapter = adapter();
        adapter.save(new DiskWatch(mid("NAS"), "/", false, null));
        adapter.save(new DiskWatch(mid("Apalveien 5"), "/", true, 70));

        assertThat(adapter.getAll()).hasSize(2);
    }

    @Test
    void aMalformedEntry_isSkipped_andTheGoodOnesStillLoad() throws IOException {
        // Same tolerance as the backup file adapters: a bad entry is skipped with a warning, never a crash
        // that takes every other watch down with it. A watch whose machine or mount no longer exists is
        // simply never looked up — it is not an error.
        Files.writeString(configDir.resolve("disk-watches.yml"),
            "watches:\n"
                + "- machineId: " + NAS.value() + "\n"
                + "  mountPoint: /volume1\n"
                + "  watched: true\n"
                + "  thresholdPercent: 90\n"
                + "- mountPoint: /orphan\n"                 // no machine id
                + "  watched: false\n"
                + "- machineId: " + NAS.value() + "\n"      // no mount point
                + "  watched: false\n"
                + "- machineId: " + NAS.value() + "\n"
                + "  mountPoint: /bad\n"
                + "  watched: true\n"
                + "  thresholdPercent: 9000\n");            // out of range

        assertThat(adapter().getAll())
            .containsExactly(new DiskWatch(NAS, "/volume1", true, 90));
    }

    @Test
    void unreadableGarbage_loadsAsNothing_ratherThanBlowingUp() throws IOException {
        Files.writeString(configDir.resolve("disk-watches.yml"), "\t: not: yaml: [");

        assertThat(adapter().getAll()).isEmpty();
    }
}
