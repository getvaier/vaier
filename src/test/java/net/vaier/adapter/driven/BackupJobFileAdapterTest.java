package net.vaier.adapter.driven;

import net.vaier.domain.BackupJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupJobFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupJobFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupJobFileAdapter(tempDir.toString());
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("colina-home")).isEmpty();
    }

    @Test
    void roundTripsIncludingListFields() {
        BackupJob job = new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir", "/etc"), List.of("*.tmp", "**/node_modules"),
            7, 4, 6, "zstd,6", true, false);

        adapter.save(job);

        // Fresh adapter reload proves it survives on disk with its list fields intact.
        BackupJobFileAdapter fresh = new BackupJobFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("colina-home")).contains(job);
        assertThat(fresh.getByName("colina-home").orElseThrow().sourcePaths())
            .containsExactly("/home/geir", "/etc");
        assertThat(fresh.getByName("colina-home").orElseThrow().excludes())
            .containsExactly("*.tmp", "**/node_modules");
    }

    // --- Back up as root ---

    @Test
    void roundTripsBackupAsRoot() {
        BackupJob asRoot = new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, true);

        adapter.save(asRoot);

        BackupJobFileAdapter fresh = new BackupJobFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("colina-home").orElseThrow().backupAsRoot()).isTrue();
    }

    /**
     * The path EVERY job file on every host takes on first load after this change: no {@code backupAsRoot} key
     * at all. It must load as {@code false} — a job never escalates itself to root because a new field appeared.
     */
    @Test
    void loadsAnExistingJobFileWithNoBackupAsRootKeyAsFalse() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("backup-jobs.yml"), """
            jobs:
            - name: colina-home
              machineName: Colina 27
              repositoryName: nas-borg
              sourcePaths:
              - /home/geir
              excludes: []
              keepDaily: 7
              keepWeekly: 4
              keepMonthly: 6
              compression: zstd,6
              enabled: true
            """);

        BackupJob loaded = adapter.getByName("colina-home").orElseThrow();

        assertThat(loaded.backupAsRoot()).isFalse();
        // And the rest of the job still loads exactly as before.
        assertThat(loaded.machineName()).isEqualTo("Colina 27");
        assertThat(loaded.sourcePaths()).containsExactly("/home/geir");
        assertThat(loaded.enabled()).isTrue();
    }

    @Test
    void roundTripsJobWithEmptyExcludes() {
        BackupJob job = new BackupJob("roon", "Roon server", "nas-borg",
            List.of("/data"), List.of(), 7, 0, 0, "zstd,6", false, false);

        adapter.save(job);

        assertThat(adapter.getByName("roon")).contains(job);
    }

    @Test
    void getByMachine_returnsEveryJobForThatMachine() {
        adapter.save(new BackupJob("a", "Colina 27", "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", "Colina 27", "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("c", "Apalveien 5", "nas-borg", List.of("/c"), List.of(), 1, 0, 0, "zstd,6", true, false));

        assertThat(adapter.getByMachine("Colina 27")).extracting(BackupJob::name)
            .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/old"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));

        assertThat(adapter.getAll()).containsExactly(
            new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(new BackupJob("a", "Colina 27", "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", "Colina 27", "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));

        adapter.deleteByName("a");

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("b");
    }
}
