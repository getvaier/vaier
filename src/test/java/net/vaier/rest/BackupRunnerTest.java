package net.vaier.rest;

import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfBackupFailureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.Archive;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BackupServer;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForRecordingBackupRuns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupRunnerTest {

    GetMachinesUseCase machines;
    GetHostCredentialUseCase credentials;
    RunRemoteCommandUseCase runner;
    InMemoryRunRecorder runs;
    GetBackupRepositoriesUseCase repositories;
    GetBackupServersUseCase servers;
    GetBackupJobsUseCase jobs;
    NotifyAdminsOfBackupFailureUseCase backupNotifier;
    ConfigResolver configResolver;
    BackupWorkDirResolver workDirResolver;
    Clock clock;
    BackupRunner backupRunner;

    /** Records runs in memory so the test can assert what was persisted. */
    static final class InMemoryRunRecorder implements ForRecordingBackupRuns {
        final List<BackupRun> recorded = new ArrayList<>();

        @Override public void record(BackupRun run) { recorded.add(run); }

        @Override public Optional<BackupRun> latestForJob(String jobName) {
            return recorded.stream().filter(r -> r.jobName().equals(jobName))
                .reduce((first, second) -> second);
        }

        @Override public List<BackupRun> getAll() { return List.copyOf(recorded); }
    }

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true);
    }

    private Machine sshMachine(String name) {
        return new Machine(name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private void hasCredential(String name) {
        when(credentials.getHostCredential(name)).thenReturn(
            Optional.of(new HostCredentialView(name, "root", AuthMethod.PASSWORD, true)));
    }

    /** Seed the run store with an in-flight RUNNING run for {@link #job()} at the fixed clock instant. */
    private void seedRunning(String runId) {
        runs.record(BackupRun.started(job(), runId, clock.instant()));
    }

    @BeforeEach
    void setUp() {
        machines = mock(GetMachinesUseCase.class);
        credentials = mock(GetHostCredentialUseCase.class);
        runner = mock(RunRemoteCommandUseCase.class);
        runs = new InMemoryRunRecorder();
        repositories = mock(GetBackupRepositoriesUseCase.class);
        servers = mock(GetBackupServersUseCase.class);
        jobs = mock(GetBackupJobsUseCase.class);
        backupNotifier = mock(NotifyAdminsOfBackupFailureUseCase.class);
        configResolver = mock(ConfigResolver.class);
        workDirResolver = mock(BackupWorkDirResolver.class);
        // Default: resolve to the SSH user's home so existing path assertions stay green.
        when(workDirResolver.workDirFor(any())).thenReturn("/home/geir/.vaier-backup");
        // Default: the repository's backup server is configured, so runs/lists reach the borg URL step.
        when(servers.getBackupServers()).thenReturn(List.of(server()));
        clock = Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC);
        backupRunner = new BackupRunner(machines, credentials, runner, runs, repositories, servers, jobs,
            backupNotifier, configResolver, clock, workDirResolver);
    }

    @Test
    void runJobRecordsRunningAndReturnsFast() {
        // Slice 3: runJob detaches borg with nohup and returns immediately once the host echoes STARTED.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        // The detached command sent over SSH still carries the borg create for this repo, backgrounded.
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains(
            "nohup sh -c \""));
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains(
            "'ssh://borg@192.168.3.3:8022/./colina'::'{hostname}-{now:%Y-%m-%dT%H:%M:%S}'"));
        // The run is recorded RUNNING; polling resolves it later.
        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(runs.getAll()).hasSize(1);
        assertThat(runs.getAll().get(0).status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(runs.getAll().get(0).jobName()).isEqualTo("colina-home");
    }

    @Test
    void runJobViaUseCaseGeneratesRunIdAndRecordsRunning() {
        // The RunBackupJobUseCase seam (used by the controller) generates the runId itself from the job +
        // clock, so the controller never has to. It then delegates to the detached run and records RUNNING.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));

        BackupRun run = backupRunner.runJob(job(), repo());

        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(run.runId()).isNotBlank();
        assertThat(run.runId()).matches("[A-Za-z0-9._-]+");
        assertThat(runs.getAll()).hasSize(1);
        assertThat(runs.getAll().get(0).runId()).isEqualTo(run.runId());
    }

    @Test
    void runJobEnsuresPassFileExistsBeforeLaunch() {
        // Slice 8: the passphrase now lives in a provisioned 0600 file the run reads via BORG_PASSCOMMAND,
        // so a run must ensure that file exists (write-if-absent) BEFORE it launches borg, or the backup
        // would fail to unlock the repo. The write-if-absent SSH call precedes the detached launch.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));

        backupRunner.runJob(job(), repo(), "run-1");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(runner);
        // First a write-if-absent of the pass file, then the detached launch — and the launch itself carries
        // no plaintext passphrase (only a BORG_PASSCOMMAND that reads the file).
        inOrder.verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("printf %s"));
        inOrder.verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup sh -c \""));
        // The detached command uses BORG_PASSCOMMAND and never the plaintext.
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("BORG_PASSCOMMAND"));
    }

    @Test
    void runJobUsesResolvedWorkDirForPassFileAndLaunch() {
        // The bug fix: the run's work dir is the SSH user's writable ~/.vaier-backup, resolved over SSH,
        // not the root-owned /var/lib. Both the pass-file ensure and the detached launch must target it —
        // the run reads BORG_PASSCOMMAND from <workDir>/<repo>.pass, so a mismatch would break the unlock.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(workDirResolver.workDirFor("Colina 27")).thenReturn("/home/geir/.vaier-backup");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));

        backupRunner.runJob(job(), repo(), "run-1");

        // The detached launch sets its work dir to the resolved home dir and reads its passcommand from
        // the pass file under it.
        verify(runner).run(eq("Colina 27"),
            org.mockito.ArgumentMatchers.contains("W=/home/geir/.vaier-backup;"));
        verify(runner).run(eq("Colina 27"),
            org.mockito.ArgumentMatchers.contains("cat /home/geir/.vaier-backup/nas-borg.pass"));
        // The pass-file ensure targets the same dir, so borg's passcommand reads the file the run wrote.
        verify(runner).run(eq("Colina 27"),
            org.mockito.ArgumentMatchers.contains("/home/geir/.vaier-backup/nas-borg.pass\" ] ||"));
    }

    @Test
    void runJobRecordsFailedWhenLaunchDoesNotConfirm() {
        // A launch that never echoes STARTED (or exits non-zero) is a failed launch, not a RUNNING run.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(1, "", "nohup: cannot run", false, "SHA256:x"));

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(runs.getAll().get(0).status()).isEqualTo(BackupRunStatus.FAILED);
    }

    @Test
    void pollRunningRunPromotesToSuccessOnRcZero() {
        seedRunning("run-1");
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("tail -c")))
            .thenReturn(new CommandResult(0, "Archive: colina-...\nDeduplicated size: 3 GB", "", false, "SHA256:x"));

        backupRunner.pollRunningRuns();

        BackupRun latest = runs.latestForJob("colina-home").orElseThrow();
        assertThat(latest.status()).isEqualTo(BackupRunStatus.SUCCESS);
        assertThat(latest.exitCode()).isEqualTo(0);
        assertThat(latest.summary()).contains("Deduplicated size");
    }

    @Test
    void pollRunningRunStaysRunningOnTransientFailure() {
        seedRunning("run-1");
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        // Poll times out (transient): the run must be left RUNNING for the next tick, not flipped to FAILED.
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null));

        backupRunner.pollRunningRuns();

        assertThat(runs.getAll()).hasSize(1);
        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.RUNNING);
    }

    @Test
    void pollReAdoptsRunningRunAfterRestart() {
        // Simulate a Vaier restart: the RUNNING run is in the store, but a fresh runner instance holds no
        // in-flight state. The next poll must re-adopt it purely from the run store and resolve it.
        seedRunning("run-1");
        BackupRunner restarted = new BackupRunner(machines, credentials, runner, runs, repositories, servers, jobs,
            backupNotifier, configResolver, clock, workDirResolver);
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(0, "DONE 0", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("tail -c")))
            .thenReturn(new CommandResult(0, "ok", "", false, "SHA256:x"));

        restarted.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.SUCCESS);
    }

    @Test
    void pollMarksUnknownWhenStaleWithNoResultFile() {
        // Poll succeeds but reports RUNNING with no rc file, long past the grace window: unresolvable.
        runs.record(BackupRun.started(job(), "run-1", Instant.parse("2026-07-07T00:00:00Z")));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(0, "RUNNING", "", false, "SHA256:x"));

        backupRunner.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.UNKNOWN);
    }

    @Test
    void listArchivesParsesJsonFromSsh() {
        // Resolve the repo, pick a job that targets it for the host to SSH from, run borg list --json and parse.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(jobs.getBackupJobs()).thenReturn(List.of(job()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        String json = "{ \"archives\": [ "
            + "{ \"archive\": \"colina-x\", \"id\": \"abc\", \"time\": \"2024-06-01T12:00:00.000000\" } ] }";
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg list --json")))
            .thenReturn(new CommandResult(0, json, "", false, "SHA256:x"));

        List<Archive> archives = backupRunner.listArchives("nas-borg");

        assertThat(archives).hasSize(1);
        assertThat(archives.get(0).name()).isEqualTo("colina-x");
        assertThat(archives.get(0).id()).isEqualTo("abc");
    }

    @Test
    void listArchivesEmptyWhenNoHostOrUnreachable() {
        // No job references the repo -> no host to SSH from -> empty, and nothing is ever run.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(jobs.getBackupJobs()).thenReturn(List.of());

        assertThat(backupRunner.listArchives("nas-borg")).isEmpty();
        verify(runner, never()).run(any(), any());

        // A job exists and the host is reachable, but borg list exits non-zero -> empty, never an exception.
        when(jobs.getBackupJobs()).thenReturn(List.of(job()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg list --json")))
            .thenReturn(new CommandResult(2, "", "Connection refused", false, null));

        assertThat(backupRunner.listArchives("nas-borg")).isEmpty();
    }

    @Test
    void skipsMachineWithoutSshAccessOrCredential() {
        // No stored credential: never runs, records a FAILED run.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        BackupRun noCred = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(noCred.status()).isEqualTo(BackupRunStatus.FAILED);
        verify(runner, never()).run(any(), any());

        // SSH access disabled: never runs, records a FAILED run.
        Machine off = new Machine("Colina 27", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, false);
        when(machines.getAllMachines()).thenReturn(List.of(off));

        BackupRun noSsh = backupRunner.runJob(job(), repo(), "run-2");

        assertThat(noSsh.status()).isEqualTo(BackupRunStatus.FAILED);
        verify(runner, never()).run(any(), any());
    }

    @Test
    void runJobRecordsFailedWhenBackupServerUnknown() {
        // Slice 2: a repository points at a Backup server by name. If that server is not configured, the
        // run cannot build a borg URL — it records FAILED with a clear reason and never contacts the host.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(servers.getBackupServers()).thenReturn(List.of());

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(runs.getAll().get(0).summary()).contains("nas-borg");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void listArchivesEmptyWhenBackupServerUnknown() {
        // The repository exists but its Backup server is not configured -> no borg URL to list from -> empty,
        // and nothing is ever run.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of());

        assertThat(backupRunner.listArchives("nas-borg")).isEmpty();
        verify(runner, never()).run(any(), any());
    }

    // --- Slice 6: Vaier-owned nightly scheduling ---

    private BackupJob disabledJob() {
        return new BackupJob("roon-media", "Roon", "nas-borg",
            List.of("/data"), List.of(), 7, 4, 6, "zstd,6", false);
    }

    private BackupJob succeededTodayJob() {
        return new BackupJob("apalveien-srv", "Apalveien 5", "nas-borg",
            List.of("/srv"), List.of(), 7, 4, 6, "zstd,6", true);
    }

    @Test
    void runDueJobsFiresOnlyDueJobsAtScheduleHour() {
        // The scheduled sweep fires only at the configured hour (2, matching the fixed 02:00 clock) and
        // only for jobs that are actually due: enabled and with no run already today.
        when(configResolver.getBackupScheduleHour()).thenReturn(2);
        BackupJob due = job();                         // enabled, never run -> due
        BackupJob disabled = disabledJob();            // disabled -> not due
        BackupJob succeeded = succeededTodayJob();     // enabled but already succeeded today -> not due
        // Seed a SUCCESS run dated today for the succeeded job so isDue sees it as already done.
        runs.record(BackupRun.fromExitCode(succeeded, "old-run", clock.instant(), clock.instant(), 0, "done"));

        when(jobs.getBackupJobs()).thenReturn(List.of(due, disabled, succeeded));
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(0, "STARTED 1", "", false, "SHA256:x"));

        backupRunner.runDueJobs();

        // Exactly one launch, for the due job on its machine. (A run also ensures the pass file exists,
        // which is a separate SSH call, so the launch is asserted specifically by its detached shape.)
        verify(runner, times(1)).run(eq("Colina 27"),
            org.mockito.ArgumentMatchers.contains("nohup sh -c \""));
        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.RUNNING);
        // The already-succeeded job still shows its prior SUCCESS, not a fresh RUNNING run.
        assertThat(runs.latestForJob("apalveien-srv").orElseThrow().status())
            .isEqualTo(BackupRunStatus.SUCCESS);
        // The disabled job never produced a run at all.
        assertThat(runs.latestForJob("roon-media")).isEmpty();
    }

    @Test
    void runDueJobsFiresNothingOutsideScheduleHour() {
        // A due job exists, but the current hour (03:00) is not the configured schedule hour (2).
        Clock atNextHour = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC);
        BackupRunner runnerAtNextHour = new BackupRunner(machines, credentials, runner, runs,
            repositories, servers, jobs, backupNotifier, configResolver, atNextHour, workDirResolver);
        when(configResolver.getBackupScheduleHour()).thenReturn(2);

        runnerAtNextHour.runDueJobs();

        verify(runner, never()).run(any(), any());
        assertThat(runs.getAll()).isEmpty();
    }

    // --- Slice 7: failure alerts on transition ---

    /** Stub the poll of a RUNNING run to settle with borg exit {@code rc}, plus its log-tail summary. */
    private void pollSettlesWith(String rc, String summary) {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(0, "DONE " + rc + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("tail -c")))
            .thenReturn(new CommandResult(0, summary, "", false, "SHA256:x"));
    }

    @Test
    void pollPromotionToFailedAlertsAdminsOnce() {
        // A RUNNING run settles FAILED on the first poll -> one failure alert. The second tick finds the
        // run already terminal (FAILED, not RUNNING) and never polls it again, so no duplicate alert.
        seedRunning("run-1");
        pollSettlesWith("2", "borg exited 2");

        backupRunner.pollRunningRuns();
        backupRunner.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.FAILED);
        org.mockito.ArgumentCaptor<BackupRun> captor = org.mockito.ArgumentCaptor.forClass(BackupRun.class);
        verify(backupNotifier, times(1)).notifyAdminsOfBackupFailure(captor.capture());
        assertThat(captor.getValue().jobName()).isEqualTo("colina-home");
        verify(backupNotifier, never()).notifyAdminsOfBackupRecovery(any());
    }

    @Test
    void steadyFailureDoesNotReAlert() {
        // The job fails night after night. Each night is a fresh run that settles FAILED, but only the
        // first failing settle crosses to failing -> exactly one alert across the persistent failures.
        seedRunning("run-1");
        pollSettlesWith("2", "borg exited 2");
        backupRunner.pollRunningRuns();

        seedRunning("run-2");
        backupRunner.pollRunningRuns();

        verify(backupNotifier, times(1)).notifyAdminsOfBackupFailure(any());
    }

    @Test
    void transientPollFailureDoesNotAlert() {
        // The poll itself times out (transient): the run is left RUNNING and the tracker is untouched,
        // so a network blip can never masquerade as a failed backup and page admins.
        seedRunning("run-1");
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null));

        backupRunner.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.RUNNING);
        verify(backupNotifier, never()).notifyAdminsOfBackupFailure(any());
        verify(backupNotifier, never()).notifyAdminsOfBackupRecovery(any());
    }

    @Test
    void successAfterFailureAllClears() {
        // Failure pages once, then a later successful run of the same job crosses back to healthy and
        // sends a single all-clear.
        seedRunning("run-1");
        pollSettlesWith("2", "borg exited 2");
        backupRunner.pollRunningRuns();

        seedRunning("run-2");
        pollSettlesWith("0", "12 files, 3 GB");
        backupRunner.pollRunningRuns();

        verify(backupNotifier, times(1)).notifyAdminsOfBackupFailure(any());
        verify(backupNotifier, times(1)).notifyAdminsOfBackupRecovery(any());
    }

    @Test
    void restartDoesNotReAlertAlreadyFailedJob() {
        // Before the restart the job already failed and was alerted; its latest run is terminal FAILED in
        // the store. After a restart (fresh runner, empty tracker) the poll must not re-alert it, because a
        // terminal run is never re-polled -> the tracker is never fed for it.
        runs.record(BackupRun.fromExitCode(job(), "run-1",
            Instant.parse("2026-07-07T02:00:00Z"), Instant.parse("2026-07-07T02:05:00Z"), 2, "boom"));
        BackupRunner restarted = new BackupRunner(machines, credentials, runner, runs, repositories, servers, jobs,
            backupNotifier, configResolver, clock, workDirResolver);

        restarted.pollRunningRuns();

        verify(runner, never()).run(any(), any());
        verify(backupNotifier, never()).notifyAdminsOfBackupFailure(any());
    }
}
