package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AuthorizeBackupClientUseCase;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.GenerateBackupServerSetupScriptUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.PrepareBackupClientUseCase;
import net.vaier.application.ProvisionBackupServerUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgClientSetupScript;
import net.vaier.domain.BorgCommand;
import net.vaier.domain.BorgServerImage;
import net.vaier.domain.BorgServerSetupScript;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
public class BackupProvisioner implements CheckBackupPrerequisitesUseCase, InitBackupRepositoryUseCase,
    ProvisionBackupServerUseCase, GenerateBackupServerSetupScriptUseCase, AuthorizeBackupClientUseCase,
    PrepareBackupClientUseCase {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final GetBackupRepositoriesUseCase repositories;
    private final GetBackupServersUseCase servers;
    private final GetBackupJobsUseCase jobs;
    private final BackupWorkDirResolver workDirResolver;
    private final ForPublishingEvents events;

    /** The SSE topic and events the backup UI subscribes to for progress (frontend never polls). */
    static final String BACKUPS_TOPIC = "backups";
    static final String PREPARE_SETTLED_EVENT = "prepare-client-settled";
    static final String PROVISION_SETTLED_EVENT = "provision-settled";

    /**
     * How often the BACKEND sweeps launched client-prepares and server-provisions for a settled result. The
     * frontend never polls; it opens an SSE stream and these sweeps push a settle event when the on-host
     * install/setup finishes.
     */
    static final long SWEEP_INTERVAL_MS = 3000;

    /** A launched detached run Vaier is waiting to settle: the host, its run id, and its work dir. */
    private record InFlightRun(String host, String runId, String workDir) {}

    /** machineName -> the in-flight prepare the backend sweep polls until it settles, then publishes over SSE. */
    private final Map<String, InFlightRun> inFlightPrepares = new ConcurrentHashMap<>();

    /** serverName -> the in-flight provision the backend sweep polls until it settles, then publishes over SSE. */
    private final Map<String, InFlightRun> inFlightProvisions = new ConcurrentHashMap<>();

    public BackupProvisioner(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             GetBackupRepositoriesUseCase repositories,
                             GetBackupServersUseCase servers,
                             GetBackupJobsUseCase jobs,
                             BackupWorkDirResolver workDirResolver,
                             ForPublishingEvents events) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.repositories = repositories;
        this.servers = servers;
        this.jobs = jobs;
        this.workDirResolver = workDirResolver;
        this.events = events;
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

    /**
     * Probe whether the machine can run borg as root. The decision of what the probe is and what its output
     * means belongs to the domain ({@link BorgClientSetupScript#rootBorgProbe}/
     * {@link BorgClientSetupScript#parseRootBorg}); this only applies the usual guards and runs it. A
     * guarded-out host, a timeout, or a thrown SSH error all report a negative — never an optimistic yes.
     */
    @Override
    public RootBorgAvailability checkRootBorg(String machineName) {
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new RootBorgAvailability(false);
        }
        log.info("Checking whether borg can run as root on {}", LogSafe.forLog(machineName));
        try {
            CommandResult result = remoteCommand.run(machine.get().name(),
                BorgClientSetupScript.rootBorgProbe());
            boolean canRunAsRoot = !result.timedOut()
                && BorgClientSetupScript.parseRootBorg(result.stdout());
            return new RootBorgAvailability(canRunAsRoot);
        } catch (Exception e) {
            log.debug("Root-borg probe on {} failed transiently: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return new RootBorgAvailability(false);
        }
    }

    @Override
    public RepoReachability checkNas(String repositoryName, String machineName) {
        Optional<BackupRepository> repo = findRepository(repositoryName);
        if (repo.isEmpty()) {
            return new RepoReachability(false);
        }
        Optional<BackupServer> server = findServer(repo.get().serverName());
        if (server.isEmpty()) {
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
                BorgCommand.reachabilityProbe(server.get()));
            boolean reachable = !result.timedOut() && BorgCommand.parseReachability(result.stdout());
            return new RepoReachability(reachable);
        } catch (Exception e) {
            log.debug("NAS reachability probe from {} failed transiently: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return new RepoReachability(false);
        }
    }

    @Override
    public ServerBorgAuth checkServerAuth(String repositoryName, String machineName,
                                          Optional<BorgVersion> clientBorgVersion) {
        Optional<BackupRepository> repo = findRepository(repositoryName);
        if (repo.isEmpty()) {
            return new ServerBorgAuth(false, Optional.empty(), false);
        }
        Optional<BackupServer> server = findServer(repo.get().serverName());
        if (server.isEmpty()) {
            return new ServerBorgAuth(false, Optional.empty(), false);
        }
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new ServerBorgAuth(false, Optional.empty(), false);
        }
        // The server's borg version is DERIVED, not probed: a managed server's restricted forced-command key
        // makes `borg --version` over SSH impossible, but because Vaier stood it up we know the pinned image's
        // borg. An adopted server is unknown — and compatibility then fails closed (never optimistically true).
        Optional<BorgVersion> serverVersion = server.get().managed()
            ? Optional.of(BorgServerImage.borgVersion()) : Optional.empty();
        String host = machine.get().name();
        String workDir = workDirResolver.workDirFor(host);
        log.info("Checking borg auth to backup server {} from {} (borg info on the repo URL)",
            LogSafe.forLog(server.get().name()), LogSafe.forLog(machineName));
        try {
            // Ensure the pass file so BORG_PASSCOMMAND resolves, then run `borg info` for THIS repo — the same
            // path a real run takes, so it validates auth AND the per-repo restriction, not just a version.
            BorgCommand.BuiltCommand ensure = BorgCommand.ensurePassFile(repo.get(), workDir);
            remoteCommand.run(host, ensure.exec());
            CommandResult result = remoteCommand.run(host,
                BorgCommand.serverAuthProbe(server.get(), repo.get(), workDir));
            if (result.timedOut()) {
                return new ServerBorgAuth(false, serverVersion, false);
            }
            // The domain decides the auth outcome from the combined output (borg writes denials/connection
            // errors to stderr and its info body to stdout, so both streams are handed over).
            boolean authOk = BorgCommand.parseServerAuth(
                combineStreams(result.stdout(), result.stderr())) == BorgCommand.ServerAuthOutcome.AUTH_OK;
            boolean compatible = authOk && BorgVersion.compatible(clientBorgVersion, serverVersion);
            return new ServerBorgAuth(authOk, serverVersion, compatible);
        } catch (Exception e) {
            log.debug("Borg auth probe from {} failed transiently: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return new ServerBorgAuth(false, serverVersion, false);
        }
    }

    /** Join a command's stdout and stderr for the domain parser (either may carry the meaningful line). */
    private static String combineStreams(String stdout, String stderr) {
        return (stdout == null ? "" : stdout) + "\n" + (stderr == null ? "" : stderr);
    }

    @Override
    public RepoInitResult initRepo(String repositoryName, String machineName) {
        Optional<BackupRepository> repo = findRepository(repositoryName);
        if (repo.isEmpty()) {
            return new RepoInitResult(false, false, "No repository named " + repositoryName);
        }
        Optional<BackupServer> server = findServer(repo.get().serverName());
        if (server.isEmpty()) {
            return new RepoInitResult(false, false, "No backup server named " + repo.get().serverName());
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

            BorgCommand.BuiltCommand init = BorgCommand.init(server.get(), repo.get(), workDir);
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

    @Override
    public Optional<String> generateSetupScript(String serverName) {
        // Orchestration only: read the server, look up the SSH owner it will be provisioned as (so the
        // script derives the borg uid/gid from that user, not a hardcoded 1000), then let the domain render.
        // Empty when no such server is configured (the REST layer turns that into a 404). This lives in the
        // rest/ orchestrator, not BackupService, because rendering now needs a credential-vault lookup and a
        // service must never call another *UseCase.
        return findServer(serverName)
            .map(s -> BorgServerSetupScript.generate(s, ownerUserFor(s)));
    }

    @Override
    public ProvisionResult provision(String serverName) {
        Optional<BackupServer> server = findServer(serverName);
        if (server.isEmpty()) {
            return new ProvisionResult(false, false, false, "No backup server named " + serverName, null);
        }
        String machineName = server.get().machineName();
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            // The host is unknown, SSH-disabled, or has no stored credential: Vaier cannot SSH in to stage the
            // script, so tell the operator to download it from the UI rather than failing opaquely.
            return new ProvisionResult(false, true, false, "Machine " + machineName
                + " is unknown, has SSH disabled, or has no stored credential — download the setup script from"
                + " the UI and run it on the host with sudo.", null);
        }
        String host = machine.get().name();
        try {
            CommandResult probe = remoteCommand.run(host, BorgServerSetupScript.dockerAvailabilityProbe());
            if (probe.timedOut() || !BorgServerSetupScript.parseDockerAvailable(probe.stdout())) {
                // No usable docker CLI over SSH (the Synology case). Vaier CAN ssh in, so it stages the setup
                // script on the host and hands the operator the one command to run — never a curl of a
                // setup.sh that sits behind admin auth.
                return stageSetupScript(server.get(), host);
            }
            // Launch detached: the setup's `docker compose up -d` pulls a ~100 MB image and would blow the
            // 20 s SSH exec cap if run synchronously. nohup it, write the exit code/output to per-run files,
            // and return as soon as STARTED is echoed — status is polled from those files.
            String script = BorgServerSetupScript.generate(server.get(), ownerUserFor(server.get()));
            String runId = server.get().provisionRunId();
            String workDir = workDirResolver.workDirFor(host);
            String launch = BorgServerSetupScript.detachedLaunch(script, runId, workDir);
            log.info("Launching detached provisioning of backup server {} on {}",
                LogSafe.forLog(serverName), LogSafe.forLog(host));
            CommandResult result = remoteCommand.run(host, launch);
            if (!result.timedOut() && result.exitCode() == 0
                && result.stdout() != null && result.stdout().contains("STARTED")) {
                // Register the launched provision so the backend sweep settles it and pushes an SSE event —
                // the frontend never polls, it just listens on the backups stream.
                inFlightProvisions.put(serverName, new InFlightRun(host, runId, workDir));
                return new ProvisionResult(false, false, true, "Provisioning started on " + host
                    + " — you'll be notified when it finishes.", null);
            }
            return new ProvisionResult(false, false, false,
                "Provisioning failed to launch: " + summaryOf(result), null);
        } catch (Exception e) {
            log.debug("Provisioning of {} on {} failed transiently: {}",
                LogSafe.forLog(serverName), LogSafe.forLog(host), e.getMessage());
            return new ProvisionResult(false, false, false, "Provisioning failed: " + e.getMessage(), null);
        }
    }

    /**
     * Stage the setup script onto {@code host} over SSH and return a {@code scriptOnly} result carrying the
     * on-host path and the exact command to run. This owns its own error handling so a staging failure never
     * propagates or reads as a hard provision failure: on any error — a non-zero write, a missing base64, an
     * SSH exception — it degrades to {@code scriptOnly} with a null path and a message telling the operator to
     * download the script from the UI instead. Never throws.
     */
    private ProvisionResult stageSetupScript(BackupServer server, String host) {
        String stagedPath = workDirResolver.workDirFor(host) + "/" + server.name() + "-borg-setup.sh";
        try {
            String script = BorgServerSetupScript.generate(server, ownerUserFor(server));
            String stage = BorgServerSetupScript.stageScript(script, stagedPath);
            log.info("Staging the setup script for backup server {} on {}",
                LogSafe.forLog(server.name()), LogSafe.forLog(host));
            CommandResult result = remoteCommand.run(host, stage);
            Optional<String> confirmed = (!result.timedOut() && result.exitCode() == 0)
                ? BorgServerSetupScript.parseStagedPath(result.stdout())
                : Optional.empty();
            if (confirmed.isPresent()) {
                String path = confirmed.get();
                return new ProvisionResult(false, true, false,
                    "Vaier cannot drive docker over SSH on " + host + ". The setup script has been placed at "
                        + path + " — run: sudo bash " + path, path);
            }
        } catch (Exception e) {
            log.debug("Staging the setup script for {} on {} failed: {}",
                LogSafe.forLog(server.name()), LogSafe.forLog(host), e.getMessage());
        }
        return new ProvisionResult(false, true, false,
            "Vaier could not stage the setup script on " + host
                + ". Download setup.sh from the UI and run it on the host with sudo.", null);
    }

    @Override
    public ProvisionStatus provisionStatus(String serverName) {
        Optional<BackupServer> server = findServer(serverName);
        if (server.isEmpty()) {
            return new ProvisionStatus(ProvisionState.RUNNING, "");
        }
        Optional<Machine> machine = reachableMachine(server.get().machineName());
        if (machine.isEmpty()) {
            return new ProvisionStatus(ProvisionState.RUNNING, "");
        }
        String host = machine.get().name();
        String runId = server.get().provisionRunId();
        String workDir = workDirResolver.workDirFor(host);
        try {
            CommandResult poll = remoteCommand.run(host, BorgCommand.pollStatus(runId, workDir));
            if (poll.timedOut() || poll.exitCode() != 0) {
                // A transient poll failure must not be read as a settled outcome — keep waiting.
                return new ProvisionStatus(ProvisionState.RUNNING, "");
            }
            Optional<Integer> exitCode = BorgCommand.parsePoll(poll.stdout());
            if (exitCode.isEmpty()) {
                return new ProvisionStatus(ProvisionState.RUNNING, "");
            }
            ProvisionState state = exitCode.get() == 0 ? ProvisionState.SUCCESS : ProvisionState.FAILED;
            return new ProvisionStatus(state, fetchLogTail(host, runId, workDir));
        } catch (Exception e) {
            log.debug("Provision status poll of {} on {} failed transiently: {}",
                LogSafe.forLog(serverName), LogSafe.forLog(host), e.getMessage());
            return new ProvisionStatus(ProvisionState.RUNNING, "");
        }
    }

    @Override
    public PrepareResult prepareClient(String machineName) {
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            // Unknown, SSH-disabled, or no stored credential: Vaier cannot SSH in to install, so tell the
            // operator to run the script on the host rather than failing opaquely.
            return new PrepareResult(false, true, false, "Machine " + machineName
                + " is unknown, has SSH disabled, or has no stored credential — download the prepare-client"
                + " script from the UI and run it on the host with sudo.", null);
        }
        String host = machine.get().name();
        try {
            // The install needs root, but Vaier SSHes as a non-root user. Probe for passwordless sudo: with
            // it, Vaier runs the script itself; without it, it degrades to staging the script for the operator.
            CommandResult probe = remoteCommand.run(host, BorgClientSetupScript.passwordlessSudoProbe());
            if (probe.timedOut() || !BorgClientSetupScript.parsePasswordlessSudo(probe.stdout())) {
                return stagePrepareScript(host, machineName);
            }
            // Launch detached: an apt/dnf install can blow the 20 s SSH exec cap. The client helper runs the
            // script under `sudo -n bash`, writes the exit code/output to per-run files, and returns as soon
            // as STARTED is echoed — status is polled from those files exactly like server provisioning.
            String script = BorgClientSetupScript.generate();
            String runId = prepareRunId(machineName);
            String workDir = workDirResolver.workDirFor(host);
            String launch = BorgClientSetupScript.detachedLaunch(script, runId, workDir);
            log.info("Launching detached borg-client install on {}", LogSafe.forLog(host));
            CommandResult result = remoteCommand.run(host, launch);
            if (!result.timedOut() && result.exitCode() == 0
                && result.stdout() != null && result.stdout().contains("STARTED")) {
                // Register the launched install so the backend sweep settles it and pushes an SSE event —
                // the frontend never polls, it just listens on the backups stream.
                inFlightPrepares.put(machineName, new InFlightRun(host, runId, workDir));
                return new PrepareResult(false, false, true,
                    "Preparing client on " + host + " — you'll be notified when it finishes.", null);
            }
            return new PrepareResult(false, false, false,
                "Client preparation failed to launch: " + summaryOf(result), null);
        } catch (Exception e) {
            log.debug("Preparing client on {} failed transiently: {}",
                LogSafe.forLog(host), e.getMessage());
            return new PrepareResult(false, false, false, "Client preparation failed: " + e.getMessage(), null);
        }
    }

    /**
     * Stage the install script onto {@code host} over SSH and return a {@code scriptOnly} result carrying the
     * on-host path and the exact {@code sudo bash <path>} command — the path taken when Vaier can SSH the host
     * but cannot gain root (no passwordless sudo). Mirrors {@link #stageSetupScript} and reuses the generic
     * {@link BorgServerSetupScript#stageScript}/{@link BorgServerSetupScript#parseStagedPath} staging helpers.
     * Owns its own error handling so a staging failure never propagates: on any error it degrades to
     * {@code scriptOnly} with a null path and a "download it" message. Never throws.
     */
    private PrepareResult stagePrepareScript(String host, String machineName) {
        String stagedPath = workDirResolver.workDirFor(host) + "/" + prepareRunId(machineName) + ".sh";
        try {
            String script = BorgClientSetupScript.generate();
            String stage = BorgServerSetupScript.stageScript(script, stagedPath);
            log.info("Staging the prepare-client script on {}", LogSafe.forLog(host));
            CommandResult result = remoteCommand.run(host, stage);
            Optional<String> confirmed = (!result.timedOut() && result.exitCode() == 0)
                ? BorgServerSetupScript.parseStagedPath(result.stdout())
                : Optional.empty();
            if (confirmed.isPresent()) {
                String path = confirmed.get();
                return new PrepareResult(false, true, false,
                    "Vaier cannot gain root over SSH on " + host + ". The prepare-client script has been placed"
                        + " at " + path + " — run: sudo bash " + path, path);
            }
        } catch (Exception e) {
            log.debug("Staging the prepare-client script on {} failed: {}",
                LogSafe.forLog(host), e.getMessage());
        }
        return new PrepareResult(false, true, false,
            "Vaier could not stage the prepare-client script on " + host
                + ". Download prepare-client.sh from the UI and run it on the host with sudo.", null);
    }

    @Override
    public PrepareStatus prepareClientStatus(String machineName) {
        Optional<Machine> machine = reachableMachine(machineName);
        if (machine.isEmpty()) {
            return new PrepareStatus(PrepareState.RUNNING, "");
        }
        String host = machine.get().name();
        String runId = prepareRunId(machineName);
        String workDir = workDirResolver.workDirFor(host);
        try {
            CommandResult poll = remoteCommand.run(host, BorgCommand.pollStatus(runId, workDir));
            if (poll.timedOut() || poll.exitCode() != 0) {
                return new PrepareStatus(PrepareState.RUNNING, "");
            }
            Optional<Integer> exitCode = BorgCommand.parsePoll(poll.stdout());
            if (exitCode.isEmpty()) {
                return new PrepareStatus(PrepareState.RUNNING, "");
            }
            PrepareState state = exitCode.get() == 0 ? PrepareState.SUCCESS : PrepareState.FAILED;
            return new PrepareStatus(state, fetchLogTail(host, runId, workDir));
        } catch (Exception e) {
            log.debug("Prepare-client status poll on {} failed transiently: {}",
                LogSafe.forLog(host), e.getMessage());
            return new PrepareStatus(PrepareState.RUNNING, "");
        }
    }

    /**
     * The backend sweep that settles launched client-prepares: for each in-flight install it reads the host's
     * {@code .rc} file over SSH (backend polling is fine — the <em>frontend</em> never polls) and, when the
     * install has finished, publishes a {@link #PREPARE_SETTLED_EVENT} on the {@link #BACKUPS_TOPIC} SSE topic
     * keyed by {@code machineName} and drops the entry so it never re-publishes. A still-running install or a
     * transient poll failure leaves the entry for the next sweep. Never throws.
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void pollInFlightPrepares() {
        sweepInFlight(inFlightPrepares, PREPARE_SETTLED_EVENT, "machineName", "prepare");
    }

    /**
     * The backend sweep that settles launched server-provisions, mirroring {@link #pollInFlightPrepares}: it
     * reads each in-flight provision's on-host {@code .rc} over SSH and, on settle, publishes a
     * {@link #PROVISION_SETTLED_EVENT} on the {@link #BACKUPS_TOPIC} topic keyed by {@code serverName}, then
     * drops the entry. The {@code GET …/provision/status} endpoint is kept for API symmetry, but the UI reacts
     * to this pushed event rather than polling it. Never throws.
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void pollInFlightProvisions() {
        sweepInFlight(inFlightProvisions, PROVISION_SETTLED_EVENT, "serverName", "provision");
    }

    /**
     * The shared settle sweep behind {@link #pollInFlightPrepares} and {@link #pollInFlightProvisions}: for
     * each in-flight detached run it reads the host's {@code .rc} over SSH and, when it has finished, removes
     * the entry and publishes {@code eventName} on {@link #BACKUPS_TOPIC} with a {@code {<idKey>,state}} payload
     * (state SUCCESS/FAILED). A still-running run or a transient poll failure leaves the entry for the next
     * sweep; a publish failure is swallowed. Never throws — one bad entry can never stall the sweep.
     */
    private void sweepInFlight(Map<String, InFlightRun> inFlight, String eventName, String idKey, String label) {
        for (Map.Entry<String, InFlightRun> entry : inFlight.entrySet()) {
            settleIfDone(inFlight, entry.getKey(), entry.getValue(), eventName, idKey, label);
        }
    }

    private void settleIfDone(Map<String, InFlightRun> inFlight, String id, InFlightRun run,
                              String eventName, String idKey, String label) {
        try {
            CommandResult poll = remoteCommand.run(run.host(), BorgCommand.pollStatus(run.runId(), run.workDir()));
            if (poll.timedOut() || poll.exitCode() != 0) {
                return;
            }
            Optional<Integer> exitCode = BorgCommand.parsePoll(poll.stdout());
            if (exitCode.isEmpty()) {
                return;   // still running — leave it for the next sweep
            }
            inFlight.remove(id);
            String state = exitCode.get() == 0 ? "SUCCESS" : "FAILED";
            events.publish(BACKUPS_TOPIC, eventName, settledJson(idKey, id, state));
            log.info("borg {} on {} settled {}", label, LogSafe.forLog(id), state);
        } catch (Exception e) {
            log.debug("Sweeping in-flight {} on {} failed transiently: {}",
                label, LogSafe.forLog(id), e.getMessage());
        }
    }

    /** The SSE payload for a settled run: {@code {"<idKey>":"…","state":"SUCCESS"}} (JSON-escaped). */
    private static String settledJson(String idKey, String idValue, String state) {
        return "{\"" + idKey + "\":\"" + jsonEscape(idValue) + "\",\"state\":\"" + state + "\"}";
    }

    /** Escape a value for embedding in a double-quoted JSON string (backslash and double quote). */
    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A deterministic per-machine run id for the prepare-client detached run's rc/log/sh files. */
    private static String prepareRunId(String machineName) {
        return "prepare-client-" + machineName.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    @Override
    public AuthorizeResult authorizeClient(String serverName, String machineName) {
        Optional<BackupServer> server = findServer(serverName);
        if (server.isEmpty()) {
            return new AuthorizeResult(false, false, false, "No backup server named " + serverName);
        }
        // The client runs keygen; the server's own machine hosts authorized_keys. Both must be reachable,
        // and they are normally distinct hosts (client vs NAS).
        Optional<Machine> client = reachableMachine(machineName);
        if (client.isEmpty()) {
            return new AuthorizeResult(false, false, false, "Machine " + machineName
                + " is unknown, has SSH disabled, or has no stored credential");
        }
        Optional<Machine> serverMachine = reachableMachine(server.get().machineName());
        if (serverMachine.isEmpty()) {
            return new AuthorizeResult(false, false, false, "Backup server machine "
                + server.get().machineName()
                + " is unknown, has SSH disabled, or has no stored credential");
        }
        // Locate authorized_keys up front: a blank serverDataPath makes this throw, and we must surface it
        // as a reasoned negative BEFORE any SSH call — never keygen a key we have nowhere to trust.
        try {
            server.get().authorizedKeysPath();
        } catch (IllegalStateException e) {
            return new AuthorizeResult(false, false, false, "Backup server " + serverName
                + " has no data path configured, so its authorized_keys cannot be located");
        }
        String clientHost = client.get().name();
        String serverHost = serverMachine.get().name();
        try {
            // Step 1 (client): generate the key pair if absent and read the public key.
            CommandResult keygen = remoteCommand.run(clientHost, BorgCommand.ensureClientKeyPair());
            if (keygen.timedOut() || keygen.exitCode() != 0) {
                return new AuthorizeResult(false, false, false,
                    "Could not read the client key on " + machineName + ": " + summaryOf(keygen));
            }
            Optional<String> publicKey = BorgCommand.parsePublicKey(keygen.stdout());
            if (publicKey.isEmpty()) {
                // Garbage (a keygen error / MOTD noise) — do NOT proceed to authorize, or it corrupts
                // authorized_keys. The public key is not a secret, but junk is never written.
                return new AuthorizeResult(false, false, false,
                    "Client " + machineName + " did not return a valid SSH public key");
            }
            // Step 2 (client): pin the server's host key BEFORE authorizing — a client that cannot verify the
            // server refuses to connect (stale pin) or has no pin to satisfy borg's non-interactive SSH.
            boolean hostKeyPinned = pinServerHostKeyOnClient(server.get(), clientHost, serverHost, machineName);
            // Step 3 (server's machine): idempotent, newline-safe upsert of a RESTRICTED entry — the key is
            // confined to just the repositories this machine backs up to on this server (never a bare key,
            // which would grant a full shell as the borg user and let one client wipe every repo).
            List<String> repoPaths = restrictPathsFor(machineName, server.get());
            boolean fellBackToRoot = repoPaths.isEmpty();
            // Never write an unrestricted key and never emit a bare --restrict-to-path: with no repo yet,
            // confine the key to the server's repository root and tell the operator to re-authorize later.
            List<String> restrictPaths = fellBackToRoot
                ? List.of("/" + server.get().baseRepoPath())
                : repoPaths;
            String authorize = BorgCommand.authorizeKey(server.get(), publicKey.get(), restrictPaths);
            log.info("Authorizing backup client {} on backup server {} ({}), restricted to {}",
                LogSafe.forLog(machineName), LogSafe.forLog(serverName), LogSafe.forLog(serverHost),
                LogSafe.forLog(String.join(", ", restrictPaths)));
            CommandResult append = remoteCommand.run(serverHost, authorize);
            if (append.timedOut() || append.exitCode() != 0) {
                return new AuthorizeResult(false, false, hostKeyPinned,
                    "Could not trust the client key on " + serverName + ": " + summaryOf(append));
            }
            boolean already = BorgCommand.wasAlreadyTrusted(append.stdout());
            String message = already
                ? "Client " + machineName + " key already trusted on " + serverName
                : "Client " + machineName + " key authorized on " + serverName;
            if (fellBackToRoot) {
                message += " — no repositories target this machine yet; restricted to the repository root."
                    + " Re-authorize after creating a job to narrow it.";
            }
            message += hostKeyPinned
                ? " — server host key pinned."
                : " — could not read the server's host key; pin it manually or re-run the setup script.";
            return new AuthorizeResult(true, already, hostKeyPinned, message);
        } catch (Exception e) {
            log.debug("Authorizing {} on {} failed transiently: {}",
                LogSafe.forLog(machineName), LogSafe.forLog(serverName), e.getMessage());
            return new AuthorizeResult(false, false, false, "Authorization failed: " + e.getMessage());
        }
    }

    /**
     * Read {@code server}'s published host keys from its own machine and pin them in the client's
     * {@code known_hosts}, so borg's non-interactive SSH has an authoritative pin (no trust-on-first-use).
     * Returns {@code true} only when the keys were read and pinned. Degrades gracefully and never throws: a
     * missing host-key file (an adopted/not-yet-provisioned server), unreadable output, junk that parses to
     * nothing, or an SSH error each leave the pin unmade and the caller still authorizes the client key —
     * never writing anything but a real key into {@code known_hosts}.
     */
    private boolean pinServerHostKeyOnClient(BackupServer server, String clientHost, String serverHost,
                                             String machineName) {
        try {
            CommandResult read = remoteCommand.run(serverHost, BorgCommand.readServerHostKeys(server));
            List<String> hostKeys = (read != null && !read.timedOut() && read.exitCode() == 0)
                ? BorgCommand.parseHostKeys(read.stdout())
                : List.of();
            if (hostKeys.isEmpty()) {
                log.info("No readable host key for backup server {} — skipping the client pin on {}",
                    LogSafe.forLog(server.name()), LogSafe.forLog(machineName));
                return false;
            }
            CommandResult pin = remoteCommand.run(clientHost, BorgCommand.pinHostKeys(server, hostKeys));
            return pin != null && !pin.timedOut() && pin.exitCode() == 0
                && BorgCommand.parsePinnedCount(pin.stdout()).isPresent();
        } catch (Exception e) {
            log.debug("Could not pin the backup server host key on {}: {}",
                LogSafe.forLog(machineName), e.getMessage());
            return false;
        }
    }

    /**
     * The SSH owner the server's host is reached as — the user the borg container must chown its data to,
     * so a later {@code authorized_keys} write over SSH as that same user succeeds. Read from the
     * credential vault; falls back to the server's own {@code borgUser} only when no credential is held
     * (the operator-runs-the-script case, where Vaier isn't SSHing in anyway).
     */
    private String ownerUserFor(BackupServer server) {
        return credentials.getHostCredential(server.machineName())
            .map(HostCredentialView::username)
            .orElse(server.borgUser());
    }

    /** The tail of the provision run's on-host log, or a blank string when it cannot be read. */
    private String fetchLogTail(String host, String runId, String workDir) {
        try {
            CommandResult logTail = remoteCommand.run(host, BorgCommand.fetchLog(runId, workDir));
            if (!logTail.timedOut() && logTail.exitCode() == 0 && logTail.stdout() != null) {
                return logTail.stdout().strip();
            }
        } catch (Exception e) {
            log.debug("Could not fetch provision log tail for {}: {}",
                LogSafe.forLog(runId), e.getMessage());
        }
        return "";
    }

    /**
     * The absolute container paths a machine's key should be restricted to on {@code server}: the borg
     * repositories that this machine's jobs target <em>on this server</em>. Joins the jobs on this machine
     * to their repositories, keeps only those living on this server, renders each as
     * {@code "/" + repoPathOn(server)} (the base path has no leading slash, so one is prepended), then sorts
     * and dedupes for a deterministic, idempotent entry line. Empty when no repository on this server is yet
     * targeted by a job on this machine (the orchestrator then falls back to the repository root).
     */
    private List<String> restrictPathsFor(String machineName, BackupServer server) {
        List<String> targetedRepoNames = jobs.getBackupJobs().stream()
            .filter(j -> j.machineName().equals(machineName))
            .map(BackupJob::repositoryName)
            .toList();
        return repositories.getBackupRepositories().stream()
            .filter(r -> targetedRepoNames.contains(r.name()))
            .filter(r -> r.serverName().equals(server.name()))
            .map(r -> "/" + r.repoPathOn(server))
            .distinct()
            .sorted()
            .toList();
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

    /** The Backup server a repository lives on, resolved by name — empty when it is not configured. */
    private Optional<BackupServer> findServer(String serverName) {
        return servers.getBackupServers().stream()
            .filter(s -> s.name().equals(serverName)).findFirst();
    }

    private static String summaryOf(CommandResult result) {
        String stderr = result.stderr();
        if (stderr != null && !stderr.isBlank()) {
            return stderr.strip();
        }
        return result.timedOut() ? "timed out" : "exit " + result.exitCode();
    }
}
