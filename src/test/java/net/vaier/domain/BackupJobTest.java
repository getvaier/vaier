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

    /** A job protecting {@code sources} with {@code excludes} — the two fields this flow maintains. */
    private BackupJob job(List<String> sources, List<String> excludes) {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            sources, excludes, 7, 4, 6, "zstd,6", true, false);
    }

    // --- stop backing up: the protected set and its exclusions, decided here on the job ------------------

    @Test
    void unprotecting_aPathAStillProtectedAncestorCovers_recordsItAsAnExclude() {
        // The reported bug. /home stays protected, so the folder cannot be dropped from the source paths —
        // the only way to actually stop backing it up is an exclude, which is what the field is for.
        Unprotection result = job(List.of("/home"), List.of())
            .unprotecting(List.of("/home/openhab/userdata/logs"));

        assertThat(result.changed()).isTrue();
        assertThat(result.jobDeleted()).isFalse();
        assertThat(result.job().sourcePaths()).containsExactly("/home");
        assertThat(result.job().excludes()).containsExactly("/home/openhab/userdata/logs");
        assertThat(result.stopped()).containsExactly("/home/openhab/userdata/logs");
    }

    @Test
    void unprotecting_aStoredSourcePath_dropsItFromTheProtectedSet() {
        Unprotection result = job(List.of("/home/geir", "/etc/nginx"), List.of())
            .unprotecting(List.of("/etc/nginx"));

        assertThat(result.changed()).isTrue();
        assertThat(result.job().sourcePaths()).containsExactly("/home/geir");
        assertThat(result.job().excludes()).isEmpty();
    }

    @Test
    void unprotecting_theLastSourcePath_saysTheJobIsGone() {
        Unprotection result = job(List.of("/home/geir"), List.of()).unprotecting(List.of("/home/geir"));

        assertThat(result.changed()).isTrue();
        assertThat(result.jobDeleted()).isTrue();
        assertThat(result.job()).isNull();
        assertThat(result.stopped()).containsExactly("/home/geir");
    }

    @Test
    void unprotecting_aPathNothingProtects_changesNothing() {
        // Neither stored nor covered: there is genuinely nothing to stop. The operator must not be told
        // otherwise, so the job comes back untouched and the outcome says so.
        BackupJob job = job(List.of("/home"), List.of());

        Unprotection result = job.unprotecting(List.of("/var/log"));

        assertThat(result.changed()).isFalse();
        assertThat(result.stopped()).isEmpty();
        assertThat(result.job()).isEqualTo(job);
    }

    @Test
    void unprotecting_aPathAlreadyExcluded_changesNothing_andNeverDuplicatesTheExclude() {
        BackupJob job = job(List.of("/home"), List.of("/home/openhab"));

        Unprotection result = job.unprotecting(List.of("/home/openhab"));

        assertThat(result.changed()).isFalse();
        assertThat(result.job().excludes()).containsExactly("/home/openhab");
    }

    @Test
    void unprotecting_aPathUnderAnExistingExclude_changesNothing_andAddsNoRedundantEntry() {
        BackupJob job = job(List.of("/home"), List.of("/home/openhab"));

        Unprotection result = job.unprotecting(List.of("/home/openhab/userdata/logs"));

        assertThat(result.changed()).isFalse();
        assertThat(result.job().excludes()).containsExactly("/home/openhab");
    }

    @Test
    void unprotecting_aFolderHoldingAnExclude_swallowsTheNarrowerExclude() {
        Unprotection result = job(List.of("/home"), List.of("/home/openhab/userdata/logs"))
            .unprotecting(List.of("/home/openhab"));

        assertThat(result.changed()).isTrue();
        assertThat(result.job().excludes()).containsExactly("/home/openhab");
    }

    @Test
    void unprotecting_aSourcePath_takesItsNowMeaninglessExcludesWithIt() {
        // Nothing under /home is backed up any more, so an exclude carving a hole inside it is dead weight.
        Unprotection result = job(List.of("/home", "/etc/nginx"), List.of("/home/openhab"))
            .unprotecting(List.of("/home"));

        assertThat(result.job().sourcePaths()).containsExactly("/etc/nginx");
        assertThat(result.job().excludes()).isEmpty();
    }

    // --- back up again: a stale exclude must never silently swallow a fresh instruction -----------------

    @Test
    void protecting_aPathThatIsCurrentlyExcluded_dropsTheExclude() {
        // "Stop backing up X" then "back up X" must leave X really backed up. Without dropping the exclude the
        // folder would wear a shield and be silently skipped by every run.
        BackupJob result = job(List.of("/home"), List.of("/home/openhab/userdata/logs"))
            .protecting(List.of("/home/openhab/userdata/logs"));

        assertThat(result.sourcePaths()).containsExactly("/home");
        assertThat(result.excludes()).isEmpty();
        assertThat(result.protectedPaths().covers("/home/openhab/userdata/logs")).isTrue();
    }

    @Test
    void protecting_aPathInsideAnExcludedFolder_dropsThatWiderExcludeToo() {
        // The exclude is an ancestor of what was just explicitly asked for. Vaier cannot exclude and protect
        // the same bytes, and the newer, explicit instruction wins — backing up more is safe, silently
        // backing up less is the bug being fixed.
        BackupJob result = job(List.of("/home"), List.of("/home/openhab"))
            .protecting(List.of("/home/openhab/userdata"));

        assertThat(result.excludes()).isEmpty();
        assertThat(result.protectedPaths().covers("/home/openhab/userdata")).isTrue();
    }

    @Test
    void protecting_addsANewSourcePath_andLeavesUnrelatedExcludesAlone() {
        BackupJob result = job(List.of("/home"), List.of("/home/openhab")).protecting(List.of("/etc/nginx"));

        assertThat(result.sourcePaths()).containsExactlyInAnyOrder("/home", "/etc/nginx");
        assertThat(result.excludes()).containsExactly("/home/openhab");
    }

    @Test
    void protectedPaths_readsTheJobsProtectionAsTheExplorerSeesIt() {
        ProtectedPaths paths = job(List.of("/home"), List.of("/home/openhab")).protectedPaths();

        assertThat(paths.covers("/home/geir")).isTrue();
        assertThat(paths.covers("/home/openhab")).isFalse();
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
