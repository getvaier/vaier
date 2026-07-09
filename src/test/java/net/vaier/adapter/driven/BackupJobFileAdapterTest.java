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
            7, 4, 6, "zstd,6", true);

        adapter.save(job);

        // Fresh adapter reload proves it survives on disk with its list fields intact.
        BackupJobFileAdapter fresh = new BackupJobFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("colina-home")).contains(job);
        assertThat(fresh.getByName("colina-home").orElseThrow().sourcePaths())
            .containsExactly("/home/geir", "/etc");
        assertThat(fresh.getByName("colina-home").orElseThrow().excludes())
            .containsExactly("*.tmp", "**/node_modules");
    }

    @Test
    void roundTripsJobWithEmptyExcludes() {
        BackupJob job = new BackupJob("roon", "Roon server", "nas-borg",
            List.of("/data"), List.of(), 7, 0, 0, "zstd,6", false);

        adapter.save(job);

        assertThat(adapter.getByName("roon")).contains(job);
    }

    @Test
    void getByMachine_returnsEveryJobForThatMachine() {
        adapter.save(new BackupJob("a", "Colina 27", "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true));
        adapter.save(new BackupJob("b", "Colina 27", "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true));
        adapter.save(new BackupJob("c", "Apalveien 5", "nas-borg", List.of("/c"), List.of(), 1, 0, 0, "zstd,6", true));

        assertThat(adapter.getByMachine("Colina 27")).extracting(BackupJob::name)
            .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/old"), List.of(), 1, 0, 0, "zstd,6", true));
        adapter.save(new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false));

        assertThat(adapter.getAll()).containsExactly(
            new BackupJob("colina-home", "Colina 27", "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(new BackupJob("a", "Colina 27", "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true));
        adapter.save(new BackupJob("b", "Colina 27", "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true));

        adapter.deleteByName("a");

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("b");
    }
}
