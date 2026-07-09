package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRunTest {

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true);
    }

    @Test
    void nonZeroExitIsFailureZeroIsSuccess() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");

        BackupRun ok = BackupRun.fromExitCode(job(), "run-1", start, end, 0, "done");
        assertThat(ok.status()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(ok.isFailure()).isFalse();
        assertThat(ok.exitCode()).isEqualTo(0);
        assertThat(ok.startedAt()).isEqualTo(start);
        assertThat(ok.finishedAt()).isEqualTo(end);

        BackupRun bad = BackupRun.fromExitCode(job(), "run-2", start, end, 2, "boom");
        assertThat(bad.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(bad.isFailure()).isTrue();
        assertThat(bad.exitCode()).isEqualTo(2);
    }

    @Test
    void startedIsRunningWithNoOutcomeYet() {
        Instant now = Instant.parse("2026-07-08T02:00:00Z");

        BackupRun run = BackupRun.started(job(), "run-3", now);

        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(run.status().isTerminal()).isFalse();
        assertThat(run.startedAt()).isEqualTo(now);
        assertThat(run.finishedAt()).isNull();
        assertThat(run.exitCode()).isNull();
        assertThat(run.jobName()).isEqualTo("colina-home");
    }

    @Test
    void completedFromMapsExitCode() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Instant done = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun running = BackupRun.started(job(), "run-9", started);

        BackupRun ok = running.completedFrom(0, done, "12 files, 3 GB");
        assertThat(ok.status()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(ok.exitCode()).isEqualTo(0);
        assertThat(ok.finishedAt()).isEqualTo(done);
        assertThat(ok.summary()).isEqualTo("12 files, 3 GB");
        // Identity of the run is preserved across completion.
        assertThat(ok.runId()).isEqualTo("run-9");
        assertThat(ok.jobName()).isEqualTo("colina-home");
        assertThat(ok.startedAt()).isEqualTo(started);

        BackupRun bad = running.completedFrom(2, done, "borg exited 2");
        assertThat(bad.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(bad.exitCode()).isEqualTo(2);
        assertThat(bad.isFailure()).isTrue();
    }

    @Test
    void failureSubjectAndBodyRenderJobMachineExitAndLink() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:05:00Z");
        BackupRun failed = BackupRun.fromExitCode(job(), "run-1", start, end, 2,
            "borg: repository is locked\nterminating");

        assertThat(failed.failureSubject())
            .isEqualTo("[Vaier] Backup failed: colina-home on Colina 27");

        String body = failed.failureBody("example.com");
        assertThat(body).contains("colina-home");        // job
        assertThat(body).contains("Colina 27");          // machine
        assertThat(body).contains("nas-borg");           // repository
        assertThat(body).contains("2");                  // exit code
        assertThat(body).contains("2026-07-08T02:05:00Z"); // finishedAt
        assertThat(body).contains("repository is locked"); // summary tail
        assertThat(body).contains("vaier.example.com");    // link built from baseDomain
    }

    @Test
    void recoverySubjectAndBodyRenderTheAllClear() {
        Instant start = Instant.parse("2026-07-08T02:00:00Z");
        Instant end = Instant.parse("2026-07-08T02:40:00Z");
        BackupRun ok = BackupRun.fromExitCode(job(), "run-2", start, end, 0, "12 files, 3 GB");

        assertThat(ok.recoverySubject())
            .isEqualTo("[Vaier] Backup recovered: colina-home on Colina 27");
        assertThat(ok.recoveryBody("example.com"))
            .contains("colina-home").contains("Colina 27").contains("vaier.example.com");
    }

    @Test
    void staleRunningBecomesUnknownAfterGrace() {
        Instant started = Instant.parse("2026-07-08T02:00:00Z");
        Duration grace = Duration.ofHours(12);
        BackupRun running = BackupRun.started(job(), "run-10", started);

        // Inside the grace window it is not yet stale.
        assertThat(running.isStaleWhileRunning(started.plus(Duration.ofHours(11)), grace)).isFalse();
        // Past the grace window a still-RUNNING run is stale and can be moved to UNKNOWN.
        assertThat(running.isStaleWhileRunning(started.plus(Duration.ofHours(13)), grace)).isTrue();

        BackupRun unknown = running.asUnknown(started.plus(Duration.ofHours(13)), "no result file after grace");
        assertThat(unknown.status()).isEqualTo(BackupRunStatus.UNKNOWN);
        assertThat(unknown.isFailure()).isTrue();
        // A terminal run is never considered stale-while-running.
        assertThat(unknown.isStaleWhileRunning(started.plus(Duration.ofHours(99)), grace)).isFalse();
    }
}
