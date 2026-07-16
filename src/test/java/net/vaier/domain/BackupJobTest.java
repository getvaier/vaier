package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackupJobTest {

    private static final ZoneOffset ZONE = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 8);

    private BackupJob job(String name) {
        return new BackupJob(name, "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    /**
     * "Back up as root" is opt-in and carried on the job: borg then reads files the SSH user cannot
     * (root-owned container volumes), instead of silently skipping them and leaving holes in the archive.
     */
    @Test
    void backupAsRoot_isCarriedOnTheJob_andIsOptIn() {
        BackupJob asUser = new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
        BackupJob asRoot = new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, true);

        assertThat(asUser.backupAsRoot()).isFalse();
        assertThat(asRoot.backupAsRoot()).isTrue();
    }

    private BackupJob enabledJob() {
        return job("colina-home");
    }

    private BackupJob disabledJob() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", false, false);
    }

    /** An instant at 02:00 on {@code date} in the test zone, so it buckets to that calendar day. */
    private Instant at(LocalDate date) {
        return date.atTime(2, 0).toInstant(ZONE);
    }

    private BackupRun succeededOn(LocalDate date) {
        return BackupRun.fromExitCode(enabledJob(), "run-" + date, at(date), at(date), 0, "ok");
    }

    private BackupRun runningOn(LocalDate date) {
        return BackupRun.started(enabledJob(), "run-" + date, at(date));
    }

    private BackupRun failedOn(LocalDate date) {
        return BackupRun.fromExitCode(enabledJob(), "run-" + date, at(date), at(date), 2, "boom");
    }

    @Test
    void runIdIsFilesystemAndShellSafeAndCarriesJobAndTime() {
        // A run id becomes part of a host path ($W/<runId>.rc) and a shell word, so it must contain only
        // filesystem/shell-safe characters. Unsafe characters in the job name are collapsed to '-'.
        String runId = job("Colina Home / weekly!").runId(1_720_404_000_000L);

        assertThat(runId).matches("[A-Za-z0-9._-]+");
        assertThat(runId).startsWith("job-");
        assertThat(runId).endsWith("-1720404000000");
        assertThat(runId).doesNotContain(" ").doesNotContain("/").doesNotContain("!");
    }

    @Test
    void runIdIsUniquePerInstantForTheSameJob() {
        BackupJob job = job("colina-home");

        assertThat(job.runId(1_000L)).isNotEqualTo(job.runId(2_000L));
    }

    // --- isDue: Vaier-owned nightly scheduling decision (Slice 6) ---

    @Test
    void isDueWhenEnabledAndNoSuccessToday() {
        // Enabled and never run -> due.
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.empty())).isTrue();
        // Enabled with its last success on a previous day -> due again today.
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.of(succeededOn(TODAY.minusDays(1))))).isTrue();
    }

    @Test
    void notDueWhenDisabled() {
        // A disabled job never runs on the schedule, even with no prior run at all.
        assertThat(disabledJob().isDue(TODAY, ZONE, Optional.empty())).isFalse();
    }

    @Test
    void notDueWhenAlreadySucceededToday() {
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.of(succeededOn(TODAY)))).isFalse();
    }

    @Test
    void notDueWhenRunningToday() {
        // An in-flight run today must not be double-fired by a later tick in the same day.
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.of(runningOn(TODAY)))).isFalse();
    }

    @Test
    void notDueWhenFailedToday() {
        // A failed attempt today blocks another auto-fire until tomorrow; a retry is manual-only.
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.of(failedOn(TODAY)))).isFalse();
        // But a failure on a previous day does not block today's scheduled attempt.
        assertThat(enabledJob().isDue(TODAY, ZONE, Optional.of(failedOn(TODAY.minusDays(1))))).isTrue();
    }

    // --- The first-back-up readying DECISION lives here on the entity, not in the service ---

    @Test
    void readyClientHostForFirstBackup_readiesThroughThePortAndReturnsTheOutcome_whenFirst() {
        // A newly-created job (first back-up for the machine) decides its host must be readied: it calls the
        // driven port with its own machine and returns the outcome.
        var readier = org.mockito.Mockito.mock(net.vaier.domain.port.ForReadyingBackupClients.class);
        var expected = new net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome(
            true, false, null, "Preparing client on Colina 27");
        org.mockito.Mockito.when(readier.readyForBackup("Colina 27")).thenReturn(expected);

        Optional<net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome> result =
            job("colina-home").readyClientHostForFirstBackup(true, readier);

        assertThat(result).contains(expected);
        org.mockito.Mockito.verify(readier).readyForBackup("Colina 27");
    }

    @Test
    void readyClientHostForFirstBackup_doesNothing_whenNotFirst() {
        // Adding paths to an existing job is not a first back-up: the host is already ready, so the port is
        // never touched and there is no outcome.
        var readier = org.mockito.Mockito.mock(net.vaier.domain.port.ForReadyingBackupClients.class);

        Optional<net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome> result =
            job("colina-home").readyClientHostForFirstBackup(false, readier);

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(readier);
    }
}
