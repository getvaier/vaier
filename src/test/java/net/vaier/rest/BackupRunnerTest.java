package net.vaier.rest;

import net.vaier.domain.MachineId;
import net.vaier.application.BackupWorkDirResolver;
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
import net.vaier.domain.port.ForPublishingEvents;
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
    ForPublishingEvents events;
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
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    private Machine sshMachine(String name) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private void hasCredential(String name) {
        when(credentials.getHostCredential(name)).thenReturn(
            Optional.of(new HostCredentialView(name, "root", AuthMethod.PASSWORD, true)));
    }

    /**
     * Stub the pre-flight {@code borg --version} probe on {@code name} to report a supported borg, so a run
     * gets past the fail-fast guard and launches. Declared AFTER any generic {@code any()} stub so Mockito's
     * last-matching-stub rule lets the specific probe stub win.
     */
    private void borgPresentOn(String name) {
        when(runner.run(eq(name), org.mockito.ArgumentMatchers.contains("borg --version")))
            .thenReturn(new CommandResult(0, "borg 1.2.8\n", "", false, "SHA256:x"));
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
        events = mock(ForPublishingEvents.class);
        // Default: resolve to the SSH user's home so existing path assertions stay green.
        when(workDirResolver.workDirFor(any())).thenReturn("/home/geir/.vaier-backup");
        when(workDirResolver.homeFor(any())).thenReturn(Optional.of("/home/geir"));
        // Default: the repository's backup server is configured, so runs/lists reach the borg URL step.
        when(servers.getBackupServers()).thenReturn(List.of(server()));
        clock = Clock.fixed(Instant.parse("2026-07-08T02:00:00Z"), ZoneOffset.UTC);
        backupRunner = new BackupRunner(machines, credentials, runner, runs, repositories, servers, jobs,
            backupNotifier, configResolver, clock, workDirResolver, events);
    }

    @Test
    void runJobRecordsRunningAndReturnsFast() {
        // Slice 3: runJob detaches borg with nohup and returns immediately once the host echoes STARTED.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));
        borgPresentOn("Colina 27");

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

    // --- Back up as root: the run escalates to sudo, and refuses rather than escalating blindly ---

    /** The same job with "Back up as root" on. */
    private BackupJob rootJob() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, true);
    }

    /**
     * A "Back up as root" job launches its borg under sudo, with the SSH user's resolved home threaded in as
     * HOME — so root's ssh finds the borg client key and the pinned server host key that live in that home.
     */
    @Test
    void runJobForABackupAsRootJobLaunchesBorgUnderSudoWithTheSshUsersHome() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));
        borgPresentOn("Colina 27");

        BackupRun run = backupRunner.runJob(rootJob(), repo(), "run-1");

        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains(
            "sudo -n HOME='/home/geir' BORG_BASE_DIR='/home/geir/.vaier-backup/root'"));
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg create"));
        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
    }

    /**
     * A "Back up as root" job whose SSH home cannot be resolved is REFUSED — recorded FAILED with a clear
     * reason, and never launched. Launching it anyway would let sudo set HOME=/root, where root's ssh finds no
     * client key and no host pin, and the run would die at the backup server with a misleading
     * "Permission denied (publickey)" hours later. Better an honest failure now.
     */
    @Test
    void runJobRefusesABackupAsRootJobWhenTheSshHomeCannotBeResolved() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));
        borgPresentOn("Colina 27");
        when(workDirResolver.homeFor("Colina 27")).thenReturn(Optional.empty());

        BackupRun run = backupRunner.runJob(rootJob(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(run.summary()).containsIgnoringCase("home");
        // Never launched: no sudo, no borg, nothing.
        verify(runner, never()).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup"));
    }

    /** A NORMAL job with an unresolvable home still runs — it never needed a home, and /tmp is writable. */
    @Test
    void runJobStillLaunchesANonRootJobWhenTheSshHomeCannotBeResolved() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));
        borgPresentOn("Colina 27");
        when(workDirResolver.homeFor("Colina 27")).thenReturn(Optional.empty());

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup sh -c \""));
    }

    @Test
    void runJobViaUseCaseGeneratesRunIdAndRecordsRunning() {
        // The RunBackupJobUseCase seam (used by the controller) generates the runId itself from the job +
        // clock, so the controller never has to. It then delegates to the detached run and records RUNNING.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "STARTED 1234", "", false, "SHA256:x"));
        borgPresentOn("Colina 27");

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
        borgPresentOn("Colina 27");

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
        borgPresentOn("Colina 27");

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
        // borg IS present, so the run gets past the fail-fast guard and it is the LAUNCH that fails here.
        borgPresentOn("Colina 27");

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
            backupNotifier, configResolver, clock, workDirResolver, events);
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
    void listMachineArchives_resolvesTheMachinesRepository_andReturnsItsArchivesNewestFirst() {
        // The time rail asks by MACHINE; the runner maps machine -> its backup job -> repository -> archives.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(jobs.getBackupJobs()).thenReturn(List.of(job())); // job() backs up "Colina 27" into "nas-borg"
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        String json = "{ \"archives\": [ "
            + "{ \"archive\": \"colina-old\", \"id\": \"a\", \"time\": \"2024-06-01T02:00:00.000000\" }, "
            + "{ \"archive\": \"colina-new\", \"id\": \"b\", \"time\": \"2024-06-03T02:00:00.000000\" } ] }";
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg list --json")))
            .thenReturn(new CommandResult(0, json, "", false, "SHA256:x"));

        List<Archive> archives = backupRunner.listMachineArchives("Colina 27");

        assertThat(archives).extracting(Archive::id).containsExactly("b", "a");
    }

    @Test
    void listMachineArchives_ofAMachineWithNoBackupJob_isEmpty() {
        when(jobs.getBackupJobs()).thenReturn(List.of(job())); // backs up "Colina 27", not "Roon"

        assertThat(backupRunner.listMachineArchives("Roon")).isEmpty();
        verify(runner, never()).run(any(), any());
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
        Machine off = new Machine(MachineId.generate(), "Colina 27", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, false);
        when(machines.getAllMachines()).thenReturn(List.of(off));

        BackupRun noSsh = backupRunner.runJob(job(), repo(), "run-2");

        assertThat(noSsh.status()).isEqualTo(BackupRunStatus.FAILED);
        verify(runner, never()).run(any(), any());
    }

    // --- Fail fast: refuse a doomed run when the client has no borg installed ---

    @Test
    void runJobFailsFastWhenBorgNotInstalledAndNeverLaunches() {
        // The NUC 02 incident: a job on a host with no borg client dies with exit 127 / "borg: not found".
        // Probe borg BEFORE the detached launch; a definite non-borg result records FAILED with a clear
        // reason and never launches (no nohup command is ever sent), so we don't waste a run + poll cycle.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg --version")))
            .thenReturn(new CommandResult(127, "", "bash: borg: command not found", false, "SHA256:x"));

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.FAILED);
        assertThat(runs.getAll().get(0).status()).isEqualTo(BackupRunStatus.FAILED);
        // The reason names the machine and points at the fix — and the fix has to be somewhere the operator
        // can reach. It used to say "run Prepare client", which was a button on the Backups page; that page
        // was deleted when the Explorer absorbed it, so the message named a control that existed nowhere.
        assertThat(run.summary()).contains("borg is not installed").contains("Colina 27");
        assertThat(run.summary()).doesNotContain("Prepare client");
        assertThat(run.needsClientReadying())
            .as("the domain marks it as the one failure a single action fixes").isTrue();
        // Crucially: no detached launch happened — the run was refused before any borg was started.
        verify(runner, never()).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup"));
    }

    @Test
    void runJobProceedsToLaunchWhenTheBorgProbeThrows() {
        // A probe EXCEPTION means "couldn't verify" — never block a working host on a flaky probe. We proceed
        // to the launch (the run itself still settles cleanly if borg really is missing).
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("borg --version")))
            .thenThrow(new RuntimeException("ssh: connection reset"));
        // Every other SSH call (pass-file ensure, detached launch) succeeds and confirms STARTED.
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 9", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("printf %s")))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));

        BackupRun run = backupRunner.runJob(job(), repo(), "run-1");

        assertThat(run.status()).isEqualTo(BackupRunStatus.RUNNING);
        verify(runner).run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("nohup"));
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
            List.of("/data"), List.of(), 7, 4, 6, "zstd,6", false, false);
    }

    private BackupJob succeededTodayJob() {
        return new BackupJob("apalveien-srv", "Apalveien 5", "nas-borg",
            List.of("/srv"), List.of(), 7, 4, 6, "zstd,6", true, false);
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
        borgPresentOn("Colina 27");

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
            repositories, servers, jobs, backupNotifier, configResolver, atNextHour, workDirResolver, events);
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
    void warningRunIsFedAsHealthyAndDoesNotPage() {
        // A borg-exit-1 WARNING settle is fed to the tracker as healthy (isFailure()==false): a job whose
        // baseline is healthy stays healthy, so a WARNING never pages admins.
        seedRunning("run-1");
        pollSettlesWith("1", "2 files skipped (permission denied)");

        backupRunner.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.WARNING);
        verify(backupNotifier, never()).notifyAdminsOfBackupFailure(any());
        verify(backupNotifier, never()).notifyAdminsOfBackupRecovery(any());
    }

    @Test
    void aRunThatCouldNotReadSomeFilesSettlesIncompleteAndPagesAdmins() {
        // The Colina 27 case, end to end through the poll that settles a run: borg exits 1 having been denied
        // on files inside the job's source paths. The archive exists but is missing data, which is trouble —
        // so it settles INCOMPLETE and travels the SAME admin-notification road a failure does. Before this,
        // it settled WARNING, told nobody, and the operator found out by reading raw borg output months later.
        seedRunning("run-1");
        pollSettlesWith("1", """
            /home/nut-http/logs/2026-04-04-14-07-07.log: open: [Errno 13] Permission denied: '2026-04-04-14-07-07.log'
            /home/nut-http/logs/2026-04-05-01-02-03.log: open: [Errno 13] Permission denied: '2026-04-05-01-02-03.log'
            """);

        backupRunner.pollRunningRuns();
        backupRunner.pollRunningRuns();

        BackupRun settled = runs.latestForJob("colina-home").orElseThrow();
        assertThat(settled.status()).isEqualTo(BackupRunStatus.INCOMPLETE);
        assertThat(settled.unreadableFiles().total()).isEqualTo(2);
        // Once, on the crossing — the second tick finds a terminal run and never re-polls it.
        verify(backupNotifier, times(1)).notifyAdminsOfBackupFailure(any());
    }

    @Test
    void warningAfterFailureAllClears() {
        // A failing job pages once; a later WARNING run (archive created, files skipped) is healthy, so it
        // crosses the job back to healthy and sends a single all-clear — a warning is a recovery, not a page.
        seedRunning("run-1");
        pollSettlesWith("2", "borg exited 2");
        backupRunner.pollRunningRuns();

        seedRunning("run-2");
        pollSettlesWith("1", "1 file skipped");
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
            backupNotifier, configResolver, clock, workDirResolver, events);

        restarted.pollRunningRuns();

        verify(runner, never()).run(any(), any());
        verify(backupNotifier, never()).notifyAdminsOfBackupFailure(any());
    }

    // --- Settle events over SSE (the frontend never polls; a settle pushes an event it consumes) ---

    @Test
    void pollSettlingToSuccessPublishesRunSettledEventOnce() {
        // When a RUNNING run settles this tick, exactly one `run-settled` event is pushed on the `backups`
        // topic so the browser can re-fetch — it never polls.
        seedRunning("run-1");
        pollSettlesWith("0", "12 files, 3 GB");

        backupRunner.pollRunningRuns();

        org.mockito.ArgumentCaptor<String> data = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(events, times(1)).publish(eq("backups"), eq("run-settled"), data.capture());
        assertThat(data.getValue()).contains("colina-home").contains("SUCCESS");
    }

    @Test
    void pollSettlingToFailedPublishesRunSettledEvent() {
        seedRunning("run-1");
        pollSettlesWith("2", "borg exited 2");

        backupRunner.pollRunningRuns();

        org.mockito.ArgumentCaptor<String> data = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(events, times(1)).publish(eq("backups"), eq("run-settled"), data.capture());
        assertThat(data.getValue()).contains("colina-home").contains("FAILED");
    }

    @Test
    void stillRunningPollPublishesNothing() {
        // A poll that still reports RUNNING (no result file, not yet stale) is not a settle: publish nothing.
        seedRunning("run-1");
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), org.mockito.ArgumentMatchers.contains("echo RUNNING")))
            .thenReturn(new CommandResult(0, "RUNNING", "", false, "SHA256:x"));

        backupRunner.pollRunningRuns();

        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void publisherThrowingDoesNotBreakTheSweep() {
        // A publish failure must never break the sweep: the run still settles to its terminal state.
        seedRunning("run-1");
        pollSettlesWith("0", "done");
        org.mockito.Mockito.doThrow(new RuntimeException("sse down"))
            .when(events).publish(any(), any(), any());

        backupRunner.pollRunningRuns();

        assertThat(runs.latestForJob("colina-home").orElseThrow().status())
            .isEqualTo(BackupRunStatus.SUCCESS);
    }
}
