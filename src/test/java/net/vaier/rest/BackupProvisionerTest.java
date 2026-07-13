package net.vaier.rest;

import net.vaier.application.AuthorizeBackupClientUseCase.AuthorizeResult;
import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.ServerBorgAuth;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionResult;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionState;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionStatus;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgServerImage;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupProvisionerTest {

    GetMachinesUseCase machines;
    GetHostCredentialUseCase credentials;
    RunRemoteCommandUseCase runner;
    GetBackupRepositoriesUseCase repositories;
    GetBackupServersUseCase servers;
    GetBackupJobsUseCase jobs;
    BackupWorkDirResolver workDirResolver;
    net.vaier.domain.port.ForPublishingEvents events;
    BackupProvisioner provisioner;

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    /** A repository whose path derives from the server (no override), on the given server. */
    private BackupRepository derivedRepo(String name, String serverName) {
        return new BackupRepository(name, serverName, null, "s3cr3t", false);
    }

    private BackupJob jobFor(String name, String machineName, String repositoryName) {
        return new BackupJob(name, machineName, repositoryName, List.of("/home"), List.of(),
            7, 4, 6, "zstd,6", true, false);
    }

    private Machine sshMachine(String name) {
        return new Machine(name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private void hasCredential(String name) {
        when(credentials.getHostCredential(name)).thenReturn(
            Optional.of(new HostCredentialView(name, "root", AuthMethod.PASSWORD, true)));
    }

    @BeforeEach
    void setUp() {
        machines = mock(GetMachinesUseCase.class);
        credentials = mock(GetHostCredentialUseCase.class);
        runner = mock(RunRemoteCommandUseCase.class);
        repositories = mock(GetBackupRepositoriesUseCase.class);
        servers = mock(GetBackupServersUseCase.class);
        jobs = mock(GetBackupJobsUseCase.class);
        workDirResolver = mock(BackupWorkDirResolver.class);
        events = mock(net.vaier.domain.port.ForPublishingEvents.class);
        // Default: resolve to the SSH user's home so existing assertions stay green.
        when(workDirResolver.workDirFor(any())).thenReturn("/home/geir/.vaier-backup");
        // Default: the repository's backup server is configured, so probes/init reach the borg URL step.
        when(servers.getBackupServers()).thenReturn(List.of(server()));
        // Default: no jobs, so authorize falls back to the base path unless a test configures jobs.
        when(jobs.getBackupJobs()).thenReturn(List.of());
        provisioner = new BackupProvisioner(machines, credentials, runner, repositories, servers,
            jobs, workDirResolver, events);
    }

    @Test
    void checkBorgDetectsInstalledVersionAndSupport() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("borg --version")))
            .thenReturn(new CommandResult(0, "borg 1.2.8\n", "", false, "SHA256:x"));

        BorgAvailability availability = provisioner.checkBorg("Colina 27");

        assertThat(availability.installed()).isTrue();
        assertThat(availability.version()).isPresent();
        assertThat(availability.version().get().major()).isEqualTo(1);
        assertThat(availability.version().get().minor()).isEqualTo(2);
        assertThat(availability.supported()).isTrue();
    }

    // --- checkRootBorg: can this machine actually run borg as root? (the "Back up as root" prerequisite) ---

    /**
     * The check that stops a "Back up as root" job from silently doing nothing: it probes {@code sudo -n borg
     * --version}, which only succeeds when the sudoers drop-in is installed AND borg is where sudo can find it.
     */
    @Test
    void checkRootBorgReportsOkWhenSudoCanRunBorg() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n borg --version")))
            .thenReturn(new CommandResult(0, "ROOT_BORG_OK\n", "", false, "SHA256:x"));

        assertThat(provisioner.checkRootBorg("Colina 27").canRunAsRoot()).isTrue();
    }

    @Test
    void checkRootBorgReportsAbsentWhenTheSudoersGrantIsMissing() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n borg --version")))
            .thenReturn(new CommandResult(0, "ROOT_BORG_ABSENT\n", "", false, "SHA256:x"));

        assertThat(provisioner.checkRootBorg("Colina 27").canRunAsRoot()).isFalse();
    }

    /** A guarded-out host (unknown / SSH off / no credential) reports a negative, never throws. */
    @Test
    void checkRootBorgReportsAbsentWhenGuardsUnmet() {
        when(machines.getAllMachines()).thenReturn(List.of());

        assertThat(provisioner.checkRootBorg("Nowhere").canRunAsRoot()).isFalse();
    }

    /** A timeout is never optimistically read as success. */
    @Test
    void checkRootBorgReportsAbsentOnATimeout() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n borg --version")))
            .thenReturn(new CommandResult(-1, "", "timeout", true, null));

        assertThat(provisioner.checkRootBorg("Colina 27").canRunAsRoot()).isFalse();
    }

    @Test
    void checkBorgReportsNotInstalled() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Apalveien 5")));
        hasCredential("Apalveien 5");
        // borg not on PATH: the shell exits non-zero with a command-not-found message.
        when(runner.run(eq("Apalveien 5"), contains("borg --version")))
            .thenReturn(new CommandResult(127, "", "bash: borg: command not found", false, "SHA256:x"));

        BorgAvailability availability = provisioner.checkBorg("Apalveien 5");

        assertThat(availability.installed()).isFalse();
        assertThat(availability.version()).isEmpty();
        assertThat(availability.supported()).isFalse();
    }

    @Test
    void checkBorgReportsNotInstalledWhenGuardsUnmet() {
        // No credential -> never contacts the host, reports not installed rather than throwing.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        BorgAvailability availability = provisioner.checkBorg("Colina 27");

        assertThat(availability.installed()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void checkNasReachableAndUnreachable() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");

        when(runner.run(eq("Colina 27"), contains("/dev/tcp/192.168.3.3/8022")))
            .thenReturn(new CommandResult(0, "NAS_OPEN\n", "", false, "SHA256:x"));
        RepoReachability open = provisioner.checkNas("nas-borg", "Colina 27");
        assertThat(open.reachable()).isTrue();

        when(runner.run(eq("Colina 27"), contains("/dev/tcp/192.168.3.3/8022")))
            .thenReturn(new CommandResult(1, "NAS_CLOSED\n", "", false, "SHA256:x"));
        RepoReachability closed = provisioner.checkNas("nas-borg", "Colina 27");
        assertThat(closed.reachable()).isFalse();
    }

    @Test
    void checkNasUnreachableWhenRepositoryUnknown() {
        when(repositories.getBackupRepositories()).thenReturn(List.of());

        RepoReachability r = provisioner.checkNas("does-not-exist", "Colina 27");

        assertThat(r.reachable()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void checkNasUnreachableWhenBackupServerUnknown() {
        // The repository exists but its Backup server is not configured -> no host:port to probe -> not
        // reachable, and nothing is ever run.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of());

        RepoReachability r = provisioner.checkNas("nas-borg", "Colina 27");

        assertThat(r.reachable()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void initRepoFailsWhenBackupServerUnknown() {
        // Without a configured Backup server there is no borg URL to init -> a negative result with a
        // reason, never an exception, and nothing is run.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of());

        RepoInitResult result = provisioner.initRepo("nas-borg", "Colina 27");

        assertThat(result.initialized()).isFalse();
        assertThat(result.message()).contains("nas-borg");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void initRepoWritesPassFileThenInitsAndSucceeds() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));

        RepoInitResult result = provisioner.initRepo("nas-borg", "Colina 27");

        assertThat(result.initialized()).isTrue();
        assertThat(result.alreadyExisted()).isFalse();
        // The pass file is provisioned before init so borg can read the passphrase from it.
        verify(runner).run(eq("Colina 27"), contains("printf %s"));
        verify(runner).run(eq("Colina 27"), contains("borg init --encryption=repokey-blake2"));
    }

    @Test
    void initRepoUsesResolvedWorkDirForPassFileAndInit() {
        // The bug fix: provisioning writes the pass file and inits under the SSH user's writable
        // ~/.vaier-backup, resolved over SSH — not the root-owned /var/lib the non-root user cannot mkdir.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(workDirResolver.workDirFor("Colina 27")).thenReturn("/home/geir/.vaier-backup");
        when(runner.run(eq("Colina 27"), any()))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));

        provisioner.initRepo("nas-borg", "Colina 27");

        // The pass-file write lands the secret under the resolved dir; borg init reads it back from the
        // same dir via BORG_PASSCOMMAND.
        verify(runner).run(eq("Colina 27"),
            contains("printf %s 's3cr3t' > \"/home/geir/.vaier-backup/nas-borg.pass\""));
        verify(runner).run(eq("Colina 27"),
            contains("BORG_PASSCOMMAND='cat /home/geir/.vaier-backup/nas-borg.pass'"));
    }

    @Test
    void initRepoIdempotentOnAlreadyExists() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        // Pass-file write succeeds; borg init fails non-zero, but only because the repo already exists.
        when(runner.run(eq("Colina 27"), contains("nas-borg.pass")))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("borg init")))
            .thenReturn(new CommandResult(2, "",
                "A repository already exists at ssh://borg@192.168.3.3:8022/./colina.", false, "SHA256:x"));

        RepoInitResult result = provisioner.initRepo("nas-borg", "Colina 27");

        // Treated as success (idempotent), flagged as already existing.
        assertThat(result.initialized()).isTrue();
        assertThat(result.alreadyExisted()).isTrue();
    }

    @Test
    void initRepoFailsOnRealError() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("nas-borg.pass")))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("borg init")))
            .thenReturn(new CommandResult(2, "", "Connection refused", false, null));

        RepoInitResult result = provisioner.initRepo("nas-borg", "Colina 27");

        assertThat(result.initialized()).isFalse();
        assertThat(result.alreadyExisted()).isFalse();
    }

    @Test
    void initRepoGuardFailsWhenNoCredential() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        RepoInitResult result = provisioner.initRepo("nas-borg", "Colina 27");

        assertThat(result.initialized()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    // --- Slice 3: provisioning a brand-new borg server ---

    @Test
    void provisionLaunchesTheSetupScriptDetachedWhenDockerPresent() {
        // The Backup server's machine "NAS" is reachable, has a credential, and exposes docker over SSH.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_OK\n", "", false, "SHA256:x"));
        // The detached launcher echoes STARTED and returns immediately (the compose pull runs on in the bg).
        when(runner.run(eq("NAS"), contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 4321\n", "", false, "SHA256:x"));

        ProvisionResult result = provisioner.provision("nas-borg");

        // It does not wait for the compose: it reports the launch started, not provisioned.
        assertThat(result.provisioned()).isFalse();
        assertThat(result.scriptOnly()).isFalse();
        assertThat(result.started()).isTrue();
        // A detached command was issued (nohup + STARTED); the image-pulling compose never ran synchronously.
        verify(runner).run(eq("NAS"), contains("nohup"));
        verify(runner, never()).run(eq("NAS"), contains("docker compose up -d"));
    }

    @Test
    void provisionReportsFailedWhenTheLaunchNeverEchoesStarted() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_OK\n", "", false, "SHA256:x"));
        // The launch fails to background (e.g. no base64 on the host): no STARTED line.
        when(runner.run(eq("NAS"), contains("nohup")))
            .thenReturn(new CommandResult(127, "", "base64: not found", false, "SHA256:x"));

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.provisioned()).isFalse();
        assertThat(result.started()).isFalse();
        assertThat(result.scriptOnly()).isFalse();
        assertThat(result.message()).containsIgnoringCase("fail");
    }

    // --- Defect 1: provision status polling ---

    @Test
    void provisionStatusReportsRunningWhileNoResultFile() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "RUNNING\n", "", false, "SHA256:x"));

        ProvisionStatus status = provisioner.provisionStatus("nas-borg");

        assertThat(status.state()).isEqualTo(ProvisionState.RUNNING);
    }

    @Test
    void provisionStatusReportsSuccessOnDoneZero() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains(".log")))
            .thenReturn(new CommandResult(0, "==> Vaier Backup server setup complete.\n", "", false, "SHA256:x"));

        ProvisionStatus status = provisioner.provisionStatus("nas-borg");

        assertThat(status.state()).isEqualTo(ProvisionState.SUCCESS);
        assertThat(status.logTail()).contains("setup complete");
    }

    @Test
    void provisionStatusReportsFailedOnDoneNonZeroWithLogTail() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 1\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains(".log")))
            .thenReturn(new CommandResult(0, "ERROR: user 'geir' does not exist\n", "", false, "SHA256:x"));

        ProvisionStatus status = provisioner.provisionStatus("nas-borg");

        assertThat(status.state()).isEqualTo(ProvisionState.FAILED);
        assertThat(status.logTail()).contains("does not exist");
    }

    @Test
    void provisionStatusLeavesRunningOnTransientPollFailure() {
        // A poll that times out or errors must not be read as a settled outcome — keep waiting.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(255, "", "connection reset", true, null));

        ProvisionStatus status = provisioner.provisionStatus("nas-borg");

        assertThat(status.state()).isEqualTo(ProvisionState.RUNNING);
    }

    // --- Defect 2: owner username flows from the credential into the generated setup script ---

    @Test
    void generateSetupScriptBakesTheOwnerUsernameFromTheCredential() {
        // Vaier SSHes to the NAS as the credential's user (e.g. geir, uid 1029) — the borg container must
        // chown its data to THAT user, so the generated script bakes it as the OWNER whose uid/gid it derives.
        when(credentials.getHostCredential("NAS")).thenReturn(
            Optional.of(new HostCredentialView("NAS", "geir", AuthMethod.PASSWORD, true)));

        Optional<String> script = provisioner.generateSetupScript("nas-borg");

        assertThat(script).isPresent();
        assertThat(script.get()).contains("OWNER=\"geir\"");
        assertThat(script.get()).contains("id -u \"$OWNER\"");
        assertThat(script.get()).doesNotContain("BORG_UID=1000");
    }

    @Test
    void generateSetupScriptUnknownServerIsEmpty() {
        assertThat(provisioner.generateSetupScript("nope")).isEmpty();
    }

    @Test
    void provisionStagesTheScriptOnTheHostWhenDockerAbsentOverSsh() {
        // The Synology case: SSH works but there is no usable docker CLI. Vaier CAN ssh in, so it writes the
        // setup script onto the host and returns scriptOnly with the exact command to run — it never asks the
        // operator to curl a setup.sh that sits behind admin auth.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_ABSENT\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("echo STAGED")))
            .thenReturn(new CommandResult(0,
                "STAGED /home/geir/.vaier-backup/nas-borg-borg-setup.sh\n", "", false, "SHA256:x"));

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.provisioned()).isFalse();
        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.started()).isFalse();
        // The staged path is returned structurally so the UI can render the command precisely.
        assertThat(result.stagedScriptPath()).isEqualTo("/home/geir/.vaier-backup/nas-borg-borg-setup.sh");
        assertThat(result.message())
            .contains("/home/geir/.vaier-backup/nas-borg-borg-setup.sh")
            .contains("sudo bash");
        // The script was staged (base64-decoded onto the host); no compose and no detached launch ran.
        verify(runner).run(eq("NAS"), contains("base64 -d"));
        verify(runner, never()).run(eq("NAS"), contains("docker compose up -d"));
        verify(runner, never()).run(eq("NAS"), contains("nohup"));
    }

    @Test
    void provisionStagingFailureDegradesToScriptOnlyWithNoPathAndAHelpfulMessage() {
        // The staging write itself fails (e.g. no base64 on the host, or a read-only work dir): degrade
        // further — scriptOnly with no staged path — and tell the operator to download the script. Never throw.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_ABSENT\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("echo STAGED")))
            .thenReturn(new CommandResult(1, "", "base64: not found", false, "SHA256:x"));

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.stagedScriptPath()).isNull();
        assertThat(result.message()).containsIgnoringCase("download");
        verify(runner, never()).run(eq("NAS"), contains("docker compose up -d"));
    }

    @Test
    void provisionStagingThrowingDegradesToScriptOnlyWithNoPathAndNeverThrows() {
        // An SSH error during staging must not propagate or read as a hard provision failure: still scriptOnly.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_ABSENT\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("echo STAGED")))
            .thenThrow(new RuntimeException("ssh: connection reset"));

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.stagedScriptPath()).isNull();
        assertThat(result.message()).containsIgnoringCase("download");
    }

    @Test
    void provisionGuardReturnsScriptOnlyWhenNoCredentialAndMakesNoSshCall() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        when(credentials.getHostCredential("NAS")).thenReturn(Optional.empty());

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.provisioned()).isFalse();
        assertThat(result.scriptOnly()).isTrue();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void provisionGuardReturnsScriptOnlyWhenMachineUnknownAndMakesNoSshCall() {
        // The Backup server names a machine that is not in the fleet -> nothing to SSH into.
        when(machines.getAllMachines()).thenReturn(List.of());

        ProvisionResult result = provisioner.provision("nas-borg");

        assertThat(result.provisioned()).isFalse();
        assertThat(result.scriptOnly()).isTrue();
        verify(runner, never()).run(any(), any());
    }

    // --- Slice 4: SSH key trust (closes #320) ---

    private static final String VALID_PUBKEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyDataHere geir@colina";

    @Test
    void authorizeKeygensOnClientThenAuthorizesOnTheServerMachineInOrder() {
        // The client "Colina 27" and the backup server's machine "NAS" are distinct hosts, both reachable.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.alreadyTrusted()).isFalse();
        // Exactly two SSH calls, in order: keygen on the CLIENT, then authorize on the SERVER's machine.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(runner);
        inOrder.verify(runner).run(eq("Colina 27"), contains("ssh-keygen"));
        inOrder.verify(runner).run(eq("NAS"), contains("authorized_keys"));
        // The two hops target different machines: the key is never authorized on the client itself.
        verify(runner, never()).run(eq("Colina 27"), contains("authorized_keys"));
        verify(runner, never()).run(eq("NAS"), contains("ssh-keygen"));
    }

    @Test
    void authorizeReportsAlreadyTrustedWhenTheKeyIsPresent() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ALREADY\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.alreadyTrusted()).isTrue();
    }

    @Test
    void authorizeUnknownServerReturnsNegativeAndMakesNoSshCall() {
        when(servers.getBackupServers()).thenReturn(List.of());

        AuthorizeResult result = provisioner.authorizeClient("nope", "Colina 27");

        assertThat(result.authorized()).isFalse();
        assertThat(result.message()).contains("nope");
        verify(runner, never()).run(any(), any());
    }

    @Test
    void authorizeGuardMakesNoSshCallWhenClientHasNoCredential() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("NAS");
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void authorizeBlankServerDataPathReturnsNegativeAndMakesNoSshCall() {
        // authorizedKeysPath() cannot be located without a data path — catch the IllegalStateException and
        // report a reasoned negative BEFORE any SSH call (never keygen into thin air).
        BackupServer noPath = new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", null, false);
        when(servers.getBackupServers()).thenReturn(List.of(noPath));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        assertThat(result.message()).isNotBlank();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void authorizeDoesNotWriteWhenTheClientReturnsGarbageInsteadOfAKey() {
        // The important one: a keygen that returns junk (Permission denied / MOTD noise) must NOT proceed to
        // authorize — otherwise the noise corrupts authorized_keys.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, "Permission denied (publickey).\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        // Never touched authorized_keys on the server.
        verify(runner, never()).run(eq("NAS"), any());
    }

    @Test
    void authorizeReportsNegativeWhenKeygenCommandFails() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(255, "", "ssh-keygen: cannot write", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        verify(runner, never()).run(eq("NAS"), any());
    }

    // --- Slice 5: server-side auth probe (kills the false all-green, survives the restricted key) ---

    /** The client borg found by checkBorg, threaded into the server-auth check to judge compatibility. */
    private static final Optional<BorgVersion> CLIENT_128 = Optional.of(new BorgVersion(1, 2, 8));

    /** A Vaier-managed server (managed=true): its borg version is derived from the pin, never probed. */
    private BackupServer managedServer() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    /**
     * Stub the client "Colina 27" so it is reachable, its pass-file write (and any non-probe command)
     * succeeds, and its {@code borg info} auth probe returns the scripted outcome.
     */
    private void clientBorgInfoReturns(int exit, String stdout, String stderr) {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), any())).thenReturn(new CommandResult(0, "", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("borg info")))
            .thenReturn(new CommandResult(exit, stdout, stderr, false, "SHA256:x"));
    }

    @Test
    void restrictedKeyDoesNotBreakTheReadinessProbe() {
        // THE regression (slice 4b × slice 5): a restricted authorized_keys entry forces `borg serve` for
        // EVERY session, so the retired `ssh 'borg --version'` probe no longer runs version — it runs borg
        // serve, reads EOF and yields no version, which the old parser mis-read as UNREACHABLE
        // (borgAuthOk=false) on a perfectly HEALTHY setup. The new probe runs `borg info` for THIS repo:
        // reaching borg at all proves the forced-command key authenticated.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of(managedServer()));
        clientBorgInfoReturns(0, "Repository ID: 3ac1f9e0\nLocation: ssh://borg@192.168.3.3:8022/./colina\n"
            + "Encrypted: Yes (repokey BLAKE2b)\n", "");

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isTrue();
        // The retired `ssh ... borg --version` probe is never sent; the readiness probe is `borg info`.
        verify(runner, never()).run(eq("Colina 27"), contains("borg --version"));
        verify(runner).run(eq("Colina 27"), contains("borg info"));
    }

    @Test
    void checkServerAuthBorgAuthOkWhenTheRepositoryDoesNotExistYet_theBootstrapCase() {
        // Fresh network: the repo has not been `borg init`-ed yet, so `borg info` returns "does not exist" —
        // but reaching that error PROVES ssh auth succeeded and borg serve ran, so borgAuthOk MUST be true.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of(managedServer()));
        clientBorgInfoReturns(2, "", "Repository /home/borg/backups/colina does not exist.");

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isTrue();
        // Managed server -> version derived from the pin; a 1.2 client vs a 1.4 server share a major.
        assertThat(auth.serverVersion()).contains(new BorgVersion(1, 4, 3));
        assertThat(auth.versionsCompatible()).isTrue();
    }

    @Test
    void checkServerAuthReportsBorgAuthFalseOnPermissionDenied() {
        // The client key is not trusted: the borg run dies with Permission denied. Not ready.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo())); // adopted server() (managed=false)
        clientBorgInfoReturns(255, "", "Permission denied (publickey,keyboard-interactive).");

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isFalse();
        assertThat(auth.versionsCompatible()).isFalse();
    }

    @Test
    void checkServerAuthManagedServerReportsDerivedVersionAndCompatibility() {
        // A Vaier-managed server: we know exactly what it runs (the pin), so serverBorgVersion is present and
        // a 1.2.8 client is compatible with the 1.4.3 it ships.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of(managedServer()));
        clientBorgInfoReturns(0, "Repository ID: abc\nEncrypted: Yes\n", "");

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isTrue();
        assertThat(auth.serverVersion()).contains(new BorgVersion(1, 4, 3));
        assertThat(auth.versionsCompatible()).isTrue();
    }

    @Test
    void checkServerAuthAdoptedServerHasUnknownVersionAndFailsClosedOnCompatibility() {
        // An adopted/registered server (managed=false): with a forced borg-serve command we CANNOT ask its
        // version, so it is unknown — and versionsCompatible fails closed (false), never optimistically green,
        // even though auth succeeds.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        clientBorgInfoReturns(0, "Repository ID: abc\nEncrypted: Yes\n", "");

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isTrue();
        assertThat(auth.serverVersion()).isEmpty();
        assertThat(auth.versionsCompatible()).isFalse();
    }

    @Test
    void checkServerAuthMakesNoSshCallWhenBackupServerUnknown() {
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(servers.getBackupServers()).thenReturn(List.of());

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isFalse();
        assertThat(auth.versionsCompatible()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void checkServerAuthMakesNoSshCallWhenClientGuardsUnmet() {
        // No credential for the client machine -> never contacts anything, reports a negative.
        when(repositories.getBackupRepositories()).thenReturn(List.of(repo()));
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        ServerBorgAuth auth = provisioner.checkServerAuth("nas-borg", "Colina 27", CLIENT_128);

        assertThat(auth.authOk()).isFalse();
        assertThat(auth.versionsCompatible()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void authorizeReportsNegativeWhenTheServerAppendFails() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(1, "", "cp: permission denied", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        assertThat(result.alreadyTrusted()).isFalse();
    }

    // --- Slice 4b: restricted, per-repo authorized_keys entries ---

    /** Capture the exact authorize command Vaier sends to the server's machine. */
    private String captureAuthorizeCommand() {
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));
        provisioner.authorizeClient("nas-borg", "Colina 27");
        // Slice 8 adds a host-key read to the server's machine too, so select the authorize command by content.
        org.mockito.ArgumentCaptor<String> cmd = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runner, atLeastOnce()).run(eq("NAS"), cmd.capture());
        return cmd.getAllValues().stream()
            .filter(c -> c.contains("authorized_keys")).findFirst().orElseThrow();
    }

    @Test
    void authorizeRestrictsToThisMachinesRepoPathsSortedAndDeduped() {
        // Colina 27 backs up to two repos on nas-borg (one referenced by two jobs -> deduped). The restrict
        // paths are the absolute container paths, sorted for a deterministic, idempotent line.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(repositories.getBackupRepositories()).thenReturn(List.of(
            derivedRepo("beta", "nas-borg"), derivedRepo("alpha", "nas-borg")));
        when(jobs.getBackupJobs()).thenReturn(List.of(
            jobFor("j1", "Colina 27", "alpha"),
            jobFor("j2", "Colina 27", "alpha"),   // same repo -> deduped to one path
            jobFor("j3", "Colina 27", "beta")));

        String cmd = captureAuthorizeCommand();

        // Sorted for a deterministic line: alpha before beta. Both are restrict paths on this machine's
        // entry. (The single-quoting of each path is asserted in BorgCommandTest; here we check selection.)
        assertThat(cmd).contains("/home/borg/backups/alpha").contains("/home/borg/backups/beta");
        assertThat(cmd.indexOf("/home/borg/backups/alpha")).isLessThan(cmd.indexOf("/home/borg/backups/beta"));
        // Deduped: alpha (referenced by two jobs) is restricted once per entry, not twice. The entry itself
        // is embedded twice in the command (the grep idempotency check + the printf append), so a deduped
        // alpha appears exactly 2x; a duplicate would show 4x.
        assertThat(cmd.split("/home/borg/backups/alpha", -1).length - 1).isEqualTo(2);
    }

    @Test
    void authorizeExcludesJobsOnOtherMachinesAndReposOnOtherServers() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(repositories.getBackupRepositories()).thenReturn(List.of(
            derivedRepo("alpha", "nas-borg"),        // Colina's repo on this server -> included
            derivedRepo("beta", "nas-borg"),         // referenced only by another machine -> excluded
            derivedRepo("gamma", "other-server")));  // Colina's repo but on another server -> excluded
        when(jobs.getBackupJobs()).thenReturn(List.of(
            jobFor("j1", "Colina 27", "alpha"),
            jobFor("j2", "Some Other Machine", "beta"),
            jobFor("j3", "Colina 27", "gamma")));

        String cmd = captureAuthorizeCommand();

        assertThat(cmd).contains("/home/borg/backups/alpha");
        assertThat(cmd).doesNotContain("/home/borg/backups/beta");
        assertThat(cmd).doesNotContain("gamma");
    }

    @Test
    void authorizeFallsBackToTheBasePathWhenNoRepositoryTargetsThisMachineYet() {
        // The operator authorizes before creating a job: no repo targets this machine, so restrict to the
        // repository root rather than write an unrestricted (full-shell) key, and say so in the message.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(repositories.getBackupRepositories()).thenReturn(List.of(derivedRepo("alpha", "nas-borg")));
        when(jobs.getBackupJobs()).thenReturn(List.of());   // no jobs on this machine
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");
        org.mockito.ArgumentCaptor<String> cmd = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runner, atLeastOnce()).run(eq("NAS"), cmd.capture());
        String authorize = cmd.getAllValues().stream()
            .filter(c -> c.contains("authorized_keys")).findFirst().orElseThrow();

        assertThat(result.authorized()).isTrue();
        // Restricted to the repository ROOT (base path), never a repo subpath and never an unrestricted key.
        assertThat(authorize).contains("--restrict-to-path");
        assertThat(authorize).contains("/home/borg/backups");
        assertThat(authorize).doesNotContain("/home/borg/backups/");   // the root, not a repo subpath
        assertThat(authorize).doesNotContain("--restrict-to-path \"");
        assertThat(result.message()).containsIgnoringCase("re-authorize");
    }

    @Test
    void authorizeStillDetectsAlreadyTrustedWithRestrictedEntries() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(repositories.getBackupRepositories()).thenReturn(List.of(derivedRepo("alpha", "nas-borg")));
        when(jobs.getBackupJobs()).thenReturn(List.of(jobFor("j1", "Colina 27", "alpha")));
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ALREADY\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.alreadyTrusted()).isTrue();
    }

    // --- Slice 8: pin the backup server's host key on the client (no trust-on-first-use) ---

    private static final String HOST_KEYS_FILE =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM89Zv3k9XqL5r7T2wF8bN0pQ1sR4uV6xY9zA2bC3dE root@borg\n"
        + "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDf3kLmN0pQ1sR4uV6xY9zA2bC3dE5fG7hI8jK9lMroot root@borg\n";

    @Test
    void authorizePinsHostKeyOnClientThenAuthorizesKeyOnServerMachineInThatOrder() {
        // Vaier reads the server's published host keys from the SERVER's machine, pins them on the CLIENT's
        // known_hosts, and only then authorizes the client key on the server's machine. The pin and the
        // authorize hit different machines and in that order (a client cannot verify the server otherwise).
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("host_keys.pub")))
            .thenReturn(new CommandResult(0, HOST_KEYS_FILE, "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("known_hosts")))
            .thenReturn(new CommandResult(0, "PINNED 2\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.hostKeyPinned()).isTrue();
        // The pin (client known_hosts) precedes the authorize (server authorized_keys), on different machines.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(runner);
        inOrder.verify(runner).run(eq("Colina 27"), contains("known_hosts"));
        inOrder.verify(runner).run(eq("NAS"), contains("authorized_keys"));
        // The host key is pinned on the client, never on the server; the key is authorized on the server only.
        verify(runner, never()).run(eq("NAS"), contains("known_hosts"));
        verify(runner, never()).run(eq("Colina 27"), contains("authorized_keys"));
    }

    @Test
    void authorizeStillAuthorizesButDoesNotPinWhenTheHostKeyFileIsMissing_theAdoptedServerCase() {
        // An adopted (or not-yet-provisioned) server never ran the setup script, so host_keys.pub is absent:
        // cat exits non-zero. Pinning is skipped, the client key is STILL authorized, and the message says so.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("host_keys.pub")))
            .thenReturn(new CommandResult(1, "", "cat: host_keys.pub: No such file", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.hostKeyPinned()).isFalse();
        assertThat(result.message()).containsIgnoringCase("host key");
        // Never attempted to write known_hosts on the client — there was nothing valid to pin.
        verify(runner, never()).run(eq("Colina 27"), contains("known_hosts"));
    }

    @Test
    void authorizePinsNothingWhenTheHostKeyFileIsGarbage() {
        // Junk in host_keys.pub (MOTD noise, a stray banner) must never be written into known_hosts.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("Colina 27");
        hasCredential("NAS");
        when(runner.run(eq("Colina 27"), contains("id_ed25519")))
            .thenReturn(new CommandResult(0, VALID_PUBKEY + "\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("host_keys.pub")))
            .thenReturn(new CommandResult(0, "Welcome to the NAS\n# not a key\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("authorized_keys")))
            .thenReturn(new CommandResult(0, "ADDED\n", "", false, "SHA256:x"));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isTrue();
        assertThat(result.hostKeyPinned()).isFalse();
        verify(runner, never()).run(eq("Colina 27"), contains("known_hosts"));
    }

    // --- Prepare client: install borg on a host that has none ---

    @Test
    void prepareClientLaunchesTheInstallDetachedWhenPasswordlessSudoIsAvailable() {
        // The host is reachable and the SSH user has passwordless sudo, so Vaier installs borg itself,
        // detached (an apt/dnf install can exceed the 20 s exec cap), and reports started.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_OK\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 7788\n", "", false, "SHA256:x"));

        var result = provisioner.prepareClient("Colina 27");

        assertThat(result.prepared()).isFalse();
        assertThat(result.scriptOnly()).isFalse();
        assertThat(result.started()).isTrue();
        // A detached command ran under sudo; the install never ran synchronously.
        verify(runner).run(eq("Colina 27"), contains("nohup"));
        verify(runner).run(eq("Colina 27"), contains("sudo -n bash"));
        verify(runner, never()).run(eq("Colina 27"), contains("apt-get install"));
    }

    @Test
    void prepareClientReportsFailedWhenTheLaunchNeverEchoesStarted() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_OK\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("nohup")))
            .thenReturn(new CommandResult(127, "", "base64: not found", false, "SHA256:x"));

        var result = provisioner.prepareClient("Colina 27");

        assertThat(result.started()).isFalse();
        assertThat(result.scriptOnly()).isFalse();
        assertThat(result.message()).containsIgnoringCase("fail");
    }

    @Test
    void prepareClientStagesTheScriptWhenPasswordlessSudoIsAbsent() {
        // No passwordless sudo: Vaier can SSH in but cannot gain root, so it stages the install script and
        // returns scriptOnly with the exact `sudo bash <path>` command — never a raw curl | sudo bash.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_ABSENT\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("echo STAGED")))
            .thenReturn(new CommandResult(0,
                "STAGED /home/geir/.vaier-backup/prepare-client-Colina-27.sh\n", "", false, "SHA256:x"));

        var result = provisioner.prepareClient("Colina 27");

        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.started()).isFalse();
        assertThat(result.stagedScriptPath())
            .isEqualTo("/home/geir/.vaier-backup/prepare-client-Colina-27.sh");
        assertThat(result.message())
            .contains("/home/geir/.vaier-backup/prepare-client-Colina-27.sh")
            .contains("sudo bash");
        // The script was staged (base64-decoded onto the host); no install and no detached launch ran.
        verify(runner).run(eq("Colina 27"), contains("base64 -d"));
        verify(runner, never()).run(eq("Colina 27"), contains("nohup"));
    }

    @Test
    void prepareClientStagingFailureDegradesToScriptOnlyWithNoPath() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_ABSENT\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("echo STAGED")))
            .thenReturn(new CommandResult(1, "", "base64: not found", false, "SHA256:x"));

        var result = provisioner.prepareClient("Colina 27");

        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.stagedScriptPath()).isNull();
        assertThat(result.message()).containsIgnoringCase("download");
    }

    @Test
    void prepareClientGuardReturnsScriptOnlyWhenNoCredentialAndMakesNoSshCall() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());

        var result = provisioner.prepareClient("Colina 27");

        assertThat(result.scriptOnly()).isTrue();
        assertThat(result.started()).isFalse();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void prepareClientGuardReturnsScriptOnlyWhenMachineUnknownAndMakesNoSshCall() {
        when(machines.getAllMachines()).thenReturn(List.of());

        var result = provisioner.prepareClient("ghost");

        assertThat(result.scriptOnly()).isTrue();
        verify(runner, never()).run(any(), any());
    }

    @Test
    void inFlightPrepareIsSweptByTheBackendAndPublishesAnSseSettleEvent_notPolledByTheFrontend() {
        // The frontend NEVER polls: after a detached prepare launches, a BACKEND scheduled sweep polls the
        // host's .rc over SSH and publishes an SSE event when it settles, which the browser consumes.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_OK\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 7788\n", "", false, "SHA256:x"));
        var started = provisioner.prepareClient("Colina 27");
        assertThat(started.started()).isTrue();

        // The install finished on the host (.rc has exit 0): the sweep publishes a settle event and does not
        // re-publish on the next sweep (the in-flight entry is cleared once settled).
        when(runner.run(eq("Colina 27"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));

        provisioner.pollInFlightPrepares();
        provisioner.pollInFlightPrepares();

        org.mockito.ArgumentCaptor<String> data = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(events, org.mockito.Mockito.times(1)).publish(
            eq("backups"), eq("prepare-client-settled"), data.capture());
        assertThat(data.getValue()).contains("Colina 27").contains("SUCCESS");
    }

    // --- Server provision: a backend sweep settles it and pushes an SSE event (the frontend never polls) ---

    /** Launch a detached provision so an in-flight provision is registered for the sweep to settle. */
    private void launchProvision() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(runner.run(eq("NAS"), contains("command -v docker")))
            .thenReturn(new CommandResult(0, "DOCKER_OK\n", "", false, "SHA256:x"));
        when(runner.run(eq("NAS"), contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 4321\n", "", false, "SHA256:x"));
        assertThat(provisioner.provision("nas-borg").started()).isTrue();
    }

    @Test
    void inFlightProvisionIsSweptByTheBackendAndPublishesAnSseSettleEvent_notPolledByTheFrontend() {
        launchProvision();

        // The setup finished on the host (.rc has exit 0): the sweep publishes a settle event and does not
        // re-publish on the next sweep (the in-flight entry is cleared once settled).
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));

        provisioner.pollInFlightProvisions();
        provisioner.pollInFlightProvisions();

        org.mockito.ArgumentCaptor<String> data = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(events, org.mockito.Mockito.times(1)).publish(
            eq("backups"), eq("provision-settled"), data.capture());
        assertThat(data.getValue()).contains("nas-borg").contains("SUCCESS");
    }

    @Test
    void inFlightProvisionStaysInFlightWhileStillRunning_noSseEvent() {
        launchProvision();

        // No .rc yet: the sweep must publish nothing and keep the entry for the next sweep.
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "RUNNING\n", "", false, "SHA256:x"));

        provisioner.pollInFlightProvisions();

        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void inFlightProvisionSweepSwallowsAPublisherError() {
        launchProvision();
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 1\n", "", false, "SHA256:x"));
        org.mockito.Mockito.doThrow(new RuntimeException("sse down"))
            .when(events).publish(any(), any(), any());

        // A publisher failure must never break the sweep.
        org.assertj.core.api.Assertions.assertThatCode(() -> provisioner.pollInFlightProvisions())
            .doesNotThrowAnyException();
    }

    @Test
    void inFlightProvisionStaysInFlightOnATransientPollFailureThenSettlesLater() {
        launchProvision();

        // A poll that times out is not a settle: nothing is published and the entry stays in flight.
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(255, "", "connection reset", true, null));
        provisioner.pollInFlightProvisions();
        verify(events, never()).publish(any(), any(), any());

        // A later sweep with a real result settles it, proving it was still in flight.
        when(runner.run(eq("NAS"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));
        provisioner.pollInFlightProvisions();
        verify(events, org.mockito.Mockito.times(1)).publish(
            eq("backups"), eq("provision-settled"), contains("SUCCESS"));
    }

    @Test
    void inFlightPrepareStaysInFlightWhileTheInstallIsStillRunning_noSseEvent() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains("sudo -n true")))
            .thenReturn(new CommandResult(0, "SUDO_OK\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains("nohup")))
            .thenReturn(new CommandResult(0, "STARTED 7788\n", "", false, "SHA256:x"));
        provisioner.prepareClient("Colina 27");

        // No .rc yet: the sweep must publish nothing and keep the entry for the next sweep.
        when(runner.run(eq("Colina 27"), contains(".rc")))
            .thenReturn(new CommandResult(0, "RUNNING\n", "", false, "SHA256:x"));

        provisioner.pollInFlightPrepares();

        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void prepareClientStatusReportsSuccessOnDoneZeroWithLogTail() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains(".rc")))
            .thenReturn(new CommandResult(0, "DONE 0\n", "", false, "SHA256:x"));
        when(runner.run(eq("Colina 27"), contains(".log")))
            .thenReturn(new CommandResult(0, "==> Vaier Backup client setup complete.\n", "", false, "SHA256:x"));

        var status = provisioner.prepareClientStatus("Colina 27");

        assertThat(status.state())
            .isEqualTo(net.vaier.application.PrepareBackupClientUseCase.PrepareState.SUCCESS);
        assertThat(status.logTail()).contains("setup complete");
    }

    @Test
    void prepareClientStatusLeavesRunningOnTransientPollFailure() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27")));
        hasCredential("Colina 27");
        when(runner.run(eq("Colina 27"), contains(".rc")))
            .thenReturn(new CommandResult(255, "", "connection reset", true, null));

        var status = provisioner.prepareClientStatus("Colina 27");

        assertThat(status.state())
            .isEqualTo(net.vaier.application.PrepareBackupClientUseCase.PrepareState.RUNNING);
    }

    @Test
    void authorizeComputingRestrictPathsMakesNoSshCallWhenGuardsUnmet() {
        // The guards still short-circuit before any SSH call even though restrict-path computation was added.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("Colina 27"), sshMachine("NAS")));
        hasCredential("NAS");
        when(credentials.getHostCredential("Colina 27")).thenReturn(Optional.empty());
        when(jobs.getBackupJobs()).thenReturn(List.of(jobFor("j1", "Colina 27", "alpha")));

        AuthorizeResult result = provisioner.authorizeClient("nas-borg", "Colina 27");

        assertThat(result.authorized()).isFalse();
        verify(runner, never()).run(any(), any());
    }
}
