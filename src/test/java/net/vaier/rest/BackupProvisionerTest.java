package net.vaier.rest;

import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupProvisionerTest {

    GetMachinesUseCase machines;
    GetHostCredentialUseCase credentials;
    RunRemoteCommandUseCase runner;
    GetBackupRepositoriesUseCase repositories;
    BackupWorkDirResolver workDirResolver;
    BackupProvisioner provisioner;

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg", "./colina", "s3cr3t", false);
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
        workDirResolver = mock(BackupWorkDirResolver.class);
        // Default: resolve to the SSH user's home so existing assertions stay green.
        when(workDirResolver.workDirFor(any())).thenReturn("/home/geir/.vaier-backup");
        provisioner = new BackupProvisioner(machines, credentials, runner, repositories, workDirResolver);
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
}
