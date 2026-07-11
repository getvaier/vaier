package net.vaier.adapter.driven;

import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRunFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupRunFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupRunFileAdapter(tempDir.toString());
    }

    private BackupRun run(String runId, String jobName, BackupRunStatus status, Integer exitCode) {
        return new BackupRun(runId, jobName, "nas-borg", "Colina 27", status,
            Instant.parse("2026-07-08T02:00:00Z"),
            status.isTerminal() ? Instant.parse("2026-07-08T02:05:00Z") : null,
            exitCode, "{hostname}-{now:%Y-%m-%dT%H:%M:%S}", "Backup completed");
    }

    @Test
    void latestForJob_emptyWhenNothingRecorded() {
        assertThat(adapter.latestForJob("colina-home")).isEmpty();
    }

    @Test
    void persistsLatestRunPerJobAcrossReload() {
        adapter.record(run("run-1", "colina-home", BackupRunStatus.RUNNING, null));
        adapter.record(run("run-2", "colina-home", BackupRunStatus.SUCCESS, 0));
        adapter.record(run("run-3", "roon", BackupRunStatus.FAILED, 2));

        // A fresh adapter reads the persisted file — the latest run per job survives a restart.
        BackupRunFileAdapter fresh = new BackupRunFileAdapter(tempDir.toString());

        assertThat(fresh.latestForJob("colina-home"))
            .contains(run("run-2", "colina-home", BackupRunStatus.SUCCESS, 0));
        assertThat(fresh.latestForJob("roon"))
            .contains(run("run-3", "roon", BackupRunStatus.FAILED, 2));
        // Only the latest run per job is retained (one per job), not the full history.
        assertThat(fresh.getAll()).hasSize(2);
    }

    @Test
    void warningRunSurvivesSaveLoadRoundTrip() {
        // A borg-exit-1 WARNING run persists like any other terminal status via valueOf/name.
        adapter.record(run("run-w", "colina-home", BackupRunStatus.WARNING, 1));

        BackupRunFileAdapter fresh = new BackupRunFileAdapter(tempDir.toString());

        assertThat(fresh.latestForJob("colina-home"))
            .contains(run("run-w", "colina-home", BackupRunStatus.WARNING, 1));
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }
}
