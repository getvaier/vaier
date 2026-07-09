package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BorgCommand;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.CommandResult;
import net.vaier.domain.Machine;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Guided-provisioning orchestrator for fleet backups, kept separate from {@link BackupRunner} so the
 * run/poll loop stays focused on running jobs. Like the runner it lives in {@code rest/} and fans several
 * narrow {@code *UseCase}s together — a web-layer concern — and never touches the SSH ports directly:
 * every probe and {@code borg init} goes through {@link RunRemoteCommandUseCase}, which resolves the
 * machine, authenticates from the vault and pins the host key.
 *
 * <p>It applies the same guards as the runner before contacting anything: an unknown machine, one with
 * SSH access off, or one Vaier holds no credential for is never reached — the check simply reports the
 * negative ("not installed" / "not reachable" / a failed init with a reason) rather than throwing. Only
 * the {@link BorgCommand.BuiltCommand#redacted() redacted} command is ever logged, and every user-supplied
 * name passes through {@link LogSafe#forLog}.
 */
@Component
@Slf4j
public class BackupProvisioner implements CheckBackupPrerequisitesUseCase, InitBackupRepositoryUseCase {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final GetBackupRepositoriesUseCase repositories;
    private final BackupWorkDirResolver workDirResolver;

    public BackupProvisioner(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             GetBackupRepositoriesUseCase repositories,
                             BackupWorkDirResolver workDirResolver) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.repositories = repositories;
        this.workDirResolver = workDirResolver;
    }

    @Override
    public BorgAvailability checkBorg(String machineName) {
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new BorgAvailability(false, Optional.empty(), false);
        }
        log.info("Checking borg availability on {}", LogSafe.forLog(machineName));
        try {
            CommandResult result = remoteCommand.run(machine.get().name(), BorgCommand.versionProbe());
            if (result.timedOut() || result.exitCode() != 0) {
                return new BorgAvailability(false, Optional.empty(), false);
            }
            Optional<BorgVersion> version = BorgVersion.parse(result.stdout());
            return new BorgAvailability(version.isPresent(), version,
                version.map(BorgVersion::isSupported).orElse(false));
        } catch (Exception e) {
            log.debug("borg version probe on {} failed transiently: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return new BorgAvailability(false, Optional.empty(), false);
        }
    }

    @Override
    public RepoReachability checkNas(String repositoryName, String machineName) {
        Optional<BackupRepository> repo = findRepository(repositoryName);
        if (repo.isEmpty()) {
            return new RepoReachability(false);
        }
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new RepoReachability(false);
        }
        log.info("Checking NAS reachability of repository {} from {}",
            LogSafe.forLog(repositoryName), LogSafe.forLog(machineName));
        try {
            CommandResult result = remoteCommand.run(machine.get().name(),
                BorgCommand.reachabilityProbe(repo.get()));
            boolean reachable = !result.timedOut() && BorgCommand.parseReachability(result.stdout());
            return new RepoReachability(reachable);
        } catch (Exception e) {
            log.debug("NAS reachability probe from {} failed transiently: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return new RepoReachability(false);
        }
    }

    @Override
    public RepoInitResult initRepo(String repositoryName, String machineName) {
        Optional<BackupRepository> repo = findRepository(repositoryName);
        if (repo.isEmpty()) {
            return new RepoInitResult(false, false, "No repository named " + repositoryName);
        }
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new RepoInitResult(false, false,
                "Machine " + machineName + " is unknown, has SSH disabled, or has no stored credential");
        }
        String host = machine.get().name();
        String workDir = workDirResolver.workDirFor(host);
        try {
            // Provision the pass file first so borg init can read the passphrase from it via BORG_PASSCOMMAND.
            BorgCommand.BuiltCommand writePass = BorgCommand.writePassFile(repo.get(), workDir);
            log.info("Installing backup passphrase file for repository {} on {}: {}",
                LogSafe.forLog(repositoryName), LogSafe.forLog(host), writePass.redacted());
            remoteCommand.run(host, writePass.exec());

            BorgCommand.BuiltCommand init = BorgCommand.init(repo.get(), workDir);
            log.info("Initialising backup repository {} on {}: {}",
                LogSafe.forLog(repositoryName), LogSafe.forLog(host), init.redacted());
            CommandResult result = remoteCommand.run(host, init.exec());
            if (!result.timedOut() && result.exitCode() == 0) {
                return new RepoInitResult(true, false, "Repository initialised");
            }
            if (BorgCommand.isRepositoryAlreadyExists(result.stderr())
                || BorgCommand.isRepositoryAlreadyExists(result.stdout())) {
                return new RepoInitResult(true, true, "Repository already exists");
            }
            return new RepoInitResult(false, false, "borg init failed: " + summaryOf(result));
        } catch (Exception e) {
            log.debug("Init of repository {} on {} failed transiently: {}",
                LogSafe.forLog(repositoryName), LogSafe.forLog(host), e.getMessage());
            return new RepoInitResult(false, false, "borg init failed: " + e.getMessage());
        }
    }

    /** A machine that is known, SSH-enabled and has a stored credential — otherwise empty (guarded out). */
    private Optional<Machine> reachableMachine(String machineName) {
        Optional<Machine> machine = machines.getAllMachines().stream()
            .filter(m -> m.name().equals(machineName)).findFirst();
        if (machine.isEmpty() || !machine.get().effectiveSshAccess()
            || credentials.getHostCredential(machine.get().name()).isEmpty()) {
            log.debug("Cannot provision via {}: machine unknown, SSH disabled, or no credential",
                LogSafe.forLog(machineName));
            return Optional.empty();
        }
        return machine;
    }

    private Optional<BackupRepository> findRepository(String repositoryName) {
        return repositories.getBackupRepositories().stream()
            .filter(r -> r.name().equals(repositoryName)).findFirst();
    }

    private static String summaryOf(CommandResult result) {
        String stderr = result.stderr();
        if (stderr != null && !stderr.isBlank()) {
            return stderr.strip();
        }
        return result.timedOut() ? "timed out" : "exit " + result.exitCode();
    }
}
