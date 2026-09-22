package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AuthorizeBackupClientUseCase;
import net.vaier.application.AuthorizeBackupClientUseCase.AuthorizeResult;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.ServerBorgAuth;
import net.vaier.application.DeleteBackupJobUseCase;
import net.vaier.application.DeleteBackupRepositoryUseCase;
import net.vaier.application.DeleteBackupServerUseCase;
import net.vaier.application.EnableBackupAsRootUseCase;
import net.vaier.application.GenerateBackupServerSetupScriptUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.ListArchivesUseCase;
import net.vaier.application.PrepareBackupClientUseCase;
import net.vaier.application.PrepareBackupClientUseCase.PrepareResult;
import net.vaier.application.PrepareBackupClientUseCase.PrepareStatus;
import net.vaier.application.ProtectMachinePathsUseCase;
import net.vaier.application.ProvisionBackupServerUseCase;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionResult;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionStatus;
import net.vaier.application.ProtectMachinePathsUseCase.ProtectionOutcome;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.SaveBackupJobUseCase;
import net.vaier.application.SaveBackupRepositoryUseCase;
import net.vaier.application.SaveBackupServerUseCase;
import net.vaier.application.WriteSurvivalKitUseCase;
import net.vaier.application.WriteSurvivalKitUseCase.SurvivalKitReport;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupAsRootOutcome;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupStoreLabel;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Unprotection;
import net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Admin CRUD for the fleet-backup configuration: {@link BackupRepository} definitions and
 * {@link BackupJob} specs. Fleet backup is freely available: every route here is reachable on any
 * instance, with no edition or licence standing in front of it.
 *
 * <p>The repository response never carries the passphrase: it reports only {@code hasPassphrase}
 * (mirrors {@code HostCredentialRestController}'s redacted view). On a PUT edit, a blank/omitted
 * passphrase keeps the stored secret rather than clearing it. Bad input surfaces as
 * {@link IllegalArgumentException} (from the domain records or {@code BackupService}) → {@code 400}
 * via {@link GlobalExceptionHandler}. Every user-supplied name passes through {@link LogSafe#forLog}.
 *
 * <p>Beyond CRUD, a job can be run on demand — {@code POST /backup-jobs/{machineId}/runs} loads the job and
 * its repository, triggers the run through {@link RunBackupJobUseCase} (the controller reaches the
 * rest-layer runner only through that use-case seam, as every controller does) and returns
 * {@code 202 Accepted} with the {@code RUNNING} run — and its latest outcome read back with
 * {@code GET /backup-jobs/{machineId}/runs}. Archive listing and provisioning arrive in later slices.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BackupRestController {

    private final SaveBackupRepositoryUseCase saveBackupRepository;
    private final GetBackupRepositoriesUseCase getBackupRepositories;
    private final DeleteBackupRepositoryUseCase deleteBackupRepository;
    private final GetBackupServersUseCase getBackupServers;
    private final SaveBackupServerUseCase saveBackupServer;
    private final DeleteBackupServerUseCase deleteBackupServer;
    private final GenerateBackupServerSetupScriptUseCase generateBackupServerSetupScript;
    private final ProvisionBackupServerUseCase provisionBackupServer;
    private final SaveBackupJobUseCase saveBackupJob;
    private final GetBackupJobsUseCase getBackupJobs;
    private final DeleteBackupJobUseCase deleteBackupJob;
    private final GetBackupRunsUseCase getBackupRuns;
    private final RunBackupJobUseCase runBackupJob;
    private final ListArchivesUseCase listArchivesUseCase;
    private final CheckBackupPrerequisitesUseCase checkBackupPrerequisites;
    private final InitBackupRepositoryUseCase initBackupRepository;
    private final GetMachinesUseCase getMachines;
    private final AuthorizeBackupClientUseCase authorizeBackupClient;
    private final PrepareBackupClientUseCase prepareBackupClient;
    private final ProtectMachinePathsUseCase protectMachinePaths;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final WriteSurvivalKitUseCase writeSurvivalKit;
    private final EnableBackupAsRootUseCase enableBackupAsRoot;


    /**
     * The backup UI's SSE stream. The frontend <strong>never polls</strong>: it opens this stream and reacts
     * to pushed events (e.g. {@code prepare-client-settled} when a launched borg-client install finishes on a
     * host — a backend sweep does the host-side polling and publishes here). Mirrors
     * {@code VpnPeerRestController}'s {@code /events} seam.
     */
    @GetMapping(value = "/backup-jobs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter backupEvents() {
        return forSubscribingToEvents.subscribe("backups");
    }

    // --- Backup servers ---

    @GetMapping("/backup-servers")
    public ResponseEntity<List<ServerResponse>> listServers() {
        return ResponseEntity.ok(getBackupServers.getBackupServers().stream()
            .map(this::toResponse).toList());
    }

    @GetMapping("/backup-servers/{name}")
    public ResponseEntity<ServerResponse> getServer(@PathVariable String name) {
        return findServer(name)
            .map(server -> ResponseEntity.ok(toResponse(server)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/backup-servers/{name}")
    public ResponseEntity<ServerResponse> saveServer(@PathVariable String name,
                                                     @RequestBody ServerRequest request) {
        log.info("Saving backup server {}", LogSafe.forLog(name));
        // A null sshPort defaults to the borg-server convention; bad input surfaces as a 400 from the record.
        int sshPort = request.sshPort() != null ? request.sshPort() : BackupServer.DEFAULT_SSH_PORT;
        // The browser names a machine; the store keys one. Resolving here means a server can never be saved
        // against a machine that does not exist — which is how two backup jobs came to run nightly against
        // machines in no registry at all.
        Machine machine = machine(request.machineId());
        BackupServer server = new BackupServer(name, machine.id(), request.host(), sshPort,
            request.borgUser(), request.baseRepoPath(), request.serverDataPath(), request.managed());
        saveBackupServer.saveBackupServer(server);
        return ResponseEntity.ok(toResponse(server));
    }

    @DeleteMapping("/backup-servers/{name}")
    public ResponseEntity<Void> deleteServer(@PathVariable String name) {
        log.info("Deleting backup server {}", LogSafe.forLog(name));
        deleteBackupServer.deleteBackupServer(name);
        return ResponseEntity.noContent().build();
    }

    /**
     * The bootstrap {@code setup.sh} a host runs to stand up this borg server from nothing — an idempotent,
     * pinned borg-server compose. 404 when the server is unknown. Mirrors {@code LanServerRestController}:
     * no {@code produces} constraint, so the success path sets {@code application/x-sh} explicitly while an
     * error still renders as JSON {@code ApiError}.
     */
    @GetMapping(value = "/backup-servers/{name}/setup.sh")
    public ResponseEntity<?> downloadSetupScript(@PathVariable String name) {
        return generateBackupServerSetupScript.generateSetupScript(name)
            .<ResponseEntity<?>>map(script -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name + "-setup.sh")
                .contentType(MediaType.parseMediaType("application/x-sh"))
                .body(script))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Provision this borg server by running its setup script on the host over SSH, where docker-over-SSH is
     * available. 404 when the server is unknown. Otherwise it never fails opaquely: when Vaier cannot run
     * the script itself (no docker over SSH, or the host is not reachable) the result reports {@code
     * scriptOnly} so the UI points the operator at the downloadable setup script instead.
     */
    @PostMapping("/backup-servers/{name}/provision")
    public ResponseEntity<ProvisionServerResponse> provisionServer(@PathVariable String name) {
        if (findServer(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Provisioning backup server {}", LogSafe.forLog(name));
        ProvisionResult result = provisionBackupServer.provision(name);
        return ResponseEntity.ok(ProvisionServerResponse.from(result));
    }

    /**
     * Report the progress of a launched provision. Provisioning is detached (it pulls a ~100 MB image), so
     * {@code POST …/provision} returns as soon as it has started and the UI polls this for the outcome:
     * {@code RUNNING} until the setup script settles, then {@code SUCCESS}/{@code FAILED} with a log tail.
     * 404 when the server is unknown.
     */
    @GetMapping("/backup-servers/{name}/provision/status")
    public ResponseEntity<ProvisionStatusResponse> provisionStatus(@PathVariable String name) {
        if (findServer(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProvisionStatus status = provisionBackupServer.provisionStatus(name);
        return ResponseEntity.ok(ProvisionStatusResponse.from(status));
    }

    /**
     * Trust a backup client host's SSH key on this server so borg — which runs on the client as the SSH
     * user, not root — can authenticate to the borg sshd (closes #320). {@code 404} when the server or the
     * machine is unknown; otherwise {@code 200} with the outcome. The use case never throws: a guarded-out
     * host, a missing data path, or an SSH failure come back as a negative result, not an error.
     */
    @PostMapping("/backup-servers/{name}/authorize/{machineId}")
    public ResponseEntity<AuthorizeResponse> authorizeClient(@PathVariable String name,
                                                             @PathVariable String machineId) {
        Optional<Machine> machine = findMachine(machineId);
        if (findServer(name).isEmpty() || machine.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Authorizing backup client {} on server {}",
            LogSafe.forLog(machineId), LogSafe.forLog(name));
        AuthorizeResult result = authorizeBackupClient.authorizeClient(name, machine.get().id());
        return ResponseEntity.ok(AuthorizeResponse.from(result));
    }

    /**
     * The machine the caller identified, or empty — the controller's own 404, never a use case's problem.
     *
     * <p>By {@link MachineId}, and a segment that does not parse as one is simply not a machine: it is not
     * looked up as a name and never was a machine's address. A name in this position would be a lookup that
     * two machines sharing a name make ambiguous, and answering it with whichever came first is how a
     * request lands on a machine nobody meant.
     */
    private Optional<Machine> findMachine(String machineId) {
        MachineId id;
        try {
            id = MachineId.of(machineId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return getMachines.getAllMachines().stream().filter(m -> id.equals(m.id())).findFirst();
    }

    /** As {@link #findMachine}, but for the write paths where an unknown machine is a 404, not a null. */
    private Machine machine(String machineId) {
        return findMachine(machineId)
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineId));
    }

    /**
     * What to call a machine in a response body — a display label beside the {@code machineId} the browser
     * actually keys on, resolved once here at the driving edge. A record pointing at a machine that has left
     * the fleet answers {@code null} rather than a stale name: "this job's machine is gone" is a fact the UI
     * should be able to show, not one to paper over.
     */
    private String machineNameOf(MachineId machineId) {
        return nameIn(getMachines.getAllMachines(), machineId);
    }

    private static String nameIn(List<Machine> fleet, MachineId machineId) {
        return fleet.stream()
            .filter(m -> m.id().equals(machineId))
            .map(Machine::name)
            .findFirst()
            .orElse(null);
    }

    // --- Backup repositories ---

    @GetMapping("/backup-repositories")
    public ResponseEntity<List<RepositoryResponse>> listRepositories() {
        // The fleet and the jobs are read once for the list, not once per row: each fleet read walks the
        // tunnel, and this list was the boot's slowest request because of it.
        List<Machine> fleet = getMachines.getAllMachines();
        List<BackupJob> jobs = getBackupJobs.getBackupJobs();
        return ResponseEntity.ok(getBackupRepositories.getBackupRepositories().stream()
            .map(repo -> toResponse(repo, fleet, jobs)).toList());
    }

    @GetMapping("/backup-repositories/{name}")
    public ResponseEntity<RepositoryResponse> getRepository(@PathVariable String name) {
        return findRepository(name)
            .map(repo -> ResponseEntity.ok(toResponse(repo, getMachines.getAllMachines(), getBackupJobs.getBackupJobs())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/backup-repositories/{name}")
    public ResponseEntity<RepositoryResponse> saveRepository(@PathVariable String name,
                                                             @RequestBody RepositoryRequest request) {
        log.info("Saving backup repository {}", LogSafe.forLog(name));
        // Keep the stored secret when the edit omits/blanks the passphrase (like the SMTP keep-existing
        // path); only a non-blank passphrase in the request replaces it.
        String passphrase = request.passphrase();
        if (passphrase == null || passphrase.isBlank()) {
            passphrase = findRepository(name).map(BackupRepository::passphrase).orElse(null);
        }
        BackupRepository repository = new BackupRepository(name, request.serverName(), request.repoPath(),
            passphrase, request.appendOnly());
        saveBackupRepository.saveBackupRepository(repository);
        return ResponseEntity.ok(toResponse(repository, getMachines.getAllMachines(), getBackupJobs.getBackupJobs()));
    }

    @DeleteMapping("/backup-repositories/{name}")
    public ResponseEntity<Void> deleteRepository(@PathVariable String name) {
        log.info("Deleting backup repository {}", LogSafe.forLog(name));
        deleteBackupRepository.deleteBackupRepository(name);
        return ResponseEntity.noContent().build();
    }

    /**
     * Browse the archives in a repository. {@code borg list} runs on a client host, so the use case picks
     * a machine from a job that targets this repository; when nothing references it (or the host is
     * unreachable) the list comes back empty rather than erroring. Returns {@code 404} only when the
     * repository itself is unknown, otherwise {@code 200} with the (possibly empty) archive list.
     */
    @GetMapping("/backup-repositories/{name}/archives")
    public ResponseEntity<List<ArchiveResponse>> listArchives(@PathVariable String name) {
        if (findRepository(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Listing archives for backup repository {}", LogSafe.forLog(name));
        return ResponseEntity.ok(listArchivesUseCase.listArchives(name).stream()
            .map(ArchiveResponse::from).toList());
    }

    // --- Backup jobs ---

    /**
     * Every job, each carrying its last outcome. The outcome rides along because the Explorer's tree colours
     * a machine's backup entry from it: a failed run has to be visible from the fleet's root, not only to an
     * operator who happens to open that machine. It is one cheap lookup per job against the same run store
     * {@code GET /backup-jobs/{machineId}/runs} reads, composed here at the driving edge rather than by making
     * either domain reach for the other.
     */
    @GetMapping("/backup-jobs")
    public ResponseEntity<List<JobResponse>> listJobs() {
        List<Machine> fleet = getMachines.getAllMachines();
        return ResponseEntity.ok(getBackupJobs.getBackupJobs().stream().map(job -> withLastRun(job, fleet)).toList());
    }

    @GetMapping("/backup-jobs/{machineId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable String machineId) {
        return findJob(machineId)
            .map(job -> ResponseEntity.ok(withLastRun(job, getMachines.getAllMachines())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** A job with its latest run's status attached, or {@code null} status when it has never run. */
    private JobResponse withLastRun(BackupJob job, List<Machine> fleet) {
        return JobResponse.from(job, nameIn(fleet, job.machineId()),
            getBackupRuns.latestForMachine(job.machineId())
                .map(BackupRun::status).map(Enum::name).orElse(null));
    }

    @PutMapping("/backup-jobs/{machineId}")
    public ResponseEntity<JobResponse> saveJob(@PathVariable String machineId,
                                               @RequestBody JobRequest request) {
        log.info("Saving the backup job for machine {}", LogSafe.forLog(machineId));
        Machine machine = machine(machineId);
        // A job's name is a label; the machine is what identifies it. Keep whatever the job is already
        // called, and fall back to the machine's name for one being written for the first time.
        String name = findJob(machineId).map(BackupJob::name).orElseGet(machine::name);
        BackupJob job = new BackupJob(name, machine.id(), request.repositoryName(),
            request.sourcePaths(), request.excludes(),
            request.keepDaily(), request.keepWeekly(), request.keepMonthly(),
            request.compression(), request.enabled(), request.backupAsRoot());
        saveBackupJob.saveBackupJob(job);
        return ResponseEntity.ok(JobResponse.from(job, machine.name()));
    }

    @DeleteMapping("/backup-jobs/{machineId}")
    public ResponseEntity<Void> deleteJob(@PathVariable String machineId) {
        log.info("Deleting the backup job for machine {}", LogSafe.forLog(machineId));
        deleteBackupJob.deleteBackupJob(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    // --- Just select and back up (protected paths) ---

    /**
     * Back up a selection of paths on a machine — the Explorer's "select files, click Back up" flow. All the
     * machinery hides behind this one call: get-or-create the machine's repository (with a backend-generated
     * passphrase) and its job, then add the posted paths to the job's protected set, normalized so no path is
     * a descendant of another. {@code 404} when the machine is unknown; {@code 409} (via
     * {@link net.vaier.domain.ConflictException}) when no backup server has been designated yet. Otherwise
     * {@code 200} with the updated job plus, on a machine's <em>first</em> back-up, a {@code provisioning}
     * object carrying the outcome of the automatic host readying the domain triggered.
     *
     * <p>The controller stays thin: it does not decide when to provision. The {@code protect} use case (its
     * domain) decides that a newly-created job means "ready this host" and returns that outcome — this handler
     * only maps it onto the response.
     */
    @PostMapping("/machines/{machineId}/backup/paths")
    public ResponseEntity<ProtectPathsResponse> protectPaths(@PathVariable String machineId,
                                                    @RequestBody ProtectPathsRequest request) {
        Optional<Machine> target = findMachine(machineId);
        if (target.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Backing up {} paths on machine {}",
            request.paths() == null ? 0 : request.paths().size(), LogSafe.forLog(machineId));
        ProtectionOutcome outcome = protectMachinePaths.protect(target.get(), request.paths());
        ProvisioningResponse provisioning = outcome.readying() == null
            ? null : ProvisioningResponse.from(outcome.readying());
        return ResponseEntity.ok(ProtectPathsResponse.from(outcome.job(), target.get().name(), provisioning));
    }

    /**
     * Stop backing up a selection of paths on a machine. A protected path (and anything under it) leaves the
     * job's protected set; a path a <em>remaining</em> protected path still covers becomes an exclude, which
     * is the only way to stop backing up a folder inside a protected ancestor. {@code 404} when the machine is
     * unknown. When the last protected path goes the job is deleted (leaving the repository intact) and the
     * response is {@code 204 No Content}; otherwise {@code 200} with the outcome.
     *
     * <p>The body says whether anything actually changed, and names the paths that stopped. It has to: a
     * request matching nothing used to answer {@code 200} with a silently unchanged job, and the browser read
     * that as "Stopped backing up 1 item." while the folder went on being backed up. The controller does not
     * work any of that out — {@link net.vaier.domain.Unprotection} is the domain's own account of what it did.
     */
    @DeleteMapping("/machines/{machineId}/backup/paths")
    public ResponseEntity<UnprotectPathsResponse> unprotectPaths(@PathVariable String machineId,
                                                                 @RequestBody ProtectPathsRequest request) {
        Optional<Machine> target = findMachine(machineId);
        if (target.isEmpty()) {
            return ResponseEntity.<UnprotectPathsResponse>notFound().build();
        }
        log.info("Stopping backup of {} paths on machine {}",
            request.paths() == null ? 0 : request.paths().size(), LogSafe.forLog(machineId));
        Unprotection outcome = protectMachinePaths.unprotect(target.get().id(), request.paths());
        if (outcome.jobDeleted()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(UnprotectPathsResponse.from(outcome, target.get().name()));
    }

    // --- Backup runs ---

    /**
     * Trigger an on-demand run of the named job. Resolves the job and its repository (404 when either is
     * unknown), launches the run through {@link RunBackupJobUseCase} and returns {@code 202 Accepted} with
     * the {@code RUNNING} run — a poll settles it to its outcome later.
     */
    @PostMapping("/backup-jobs/{machineId}/runs")
    public ResponseEntity<RunResponse> runJob(@PathVariable String machineId) {
        log.info("Backing up machine {} on demand", LogSafe.forLog(machineId));
        Optional<BackupJob> job = findJob(machineId);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<BackupRepository> repo = findRepository(job.get().repositoryName());
        if (repo.isEmpty() || findServer(repo.get().serverName()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BackupRun run = runBackupJob.runJob(job.get(), repo.get());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(RunResponse.from(run, machineNameOf(run.machineId())));
    }

    /** The latest run for the named job, or {@code 404} when it has never run (matches the CRUD lookups). */
    @GetMapping("/backup-jobs/{machineId}/runs")
    public ResponseEntity<RunResponse> getRuns(@PathVariable String machineId) {
        return getBackupRuns.latestForMachine(MachineId.of(machineId))
            .map(run -> ResponseEntity.ok(RunResponse.from(run, machineNameOf(run.machineId()))))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // --- Guided provisioning ---

    /**
     * Report a job's host readiness for the provisioning wizard: whether borg is installed there (and new
     * enough), and whether that host can reach the job's NAS borg port over the tunnel. Resolves the job
     * to its machine and repository (404 when the job is unknown) and runs both checks through
     * {@link CheckBackupPrerequisitesUseCase}; neither check throws, so a negative just reports the
     * relevant flag false.
     */
    @GetMapping("/backup-jobs/{machineId}/provision/check")
    public ResponseEntity<ProvisionCheckResponse> provisionCheck(@PathVariable String machineId) {
        Optional<BackupJob> job = findJob(machineId);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<BackupRepository> repo = findRepository(job.get().repositoryName());
        if (repo.isEmpty() || findServer(repo.get().serverName()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Checking backup prerequisites for machine {}", LogSafe.forLog(machineId));
        BorgAvailability borg = checkBackupPrerequisites.checkBorg(job.get().machineId());
        RepoReachability nas = checkBackupPrerequisites.checkNas(job.get().repositoryName(),
            job.get().machineId());
        // The decisive probe: authenticate to the server and compare versions, threading the client's own
        // borg version in so compatibility is judged against a real server version — not assumed.
        ServerBorgAuth auth = checkBackupPrerequisites.checkServerAuth(job.get().repositoryName(),
            job.get().machineId(), borg.version());
        // "Back up as root" is only a prerequisite for a job that asked for it: a job with the toggle off does
        // not need the sudo grant and must never be shown as failing a check it will never use — so it is not
        // even probed (no pointless SSH round trip).
        boolean backupAsRoot = job.get().backupAsRoot();
        boolean rootBorgOk = backupAsRoot
            && checkBackupPrerequisites.checkRootBorg(job.get().machineId()).canRunAsRoot();
        return ResponseEntity.ok(ProvisionCheckResponse.from(borg, nas, auth, backupAsRoot, rootBorgOk));
    }

    /**
     * Initialise a repository on the NAS from a host that references it. A repository has no host of its
     * own, so this picks a machine from a job targeting it — a first enabled job, falling back to any job.
     * Returns {@code 404} when the repository is unknown and {@code 409 Conflict} when no job references it
     * (no host to init from). Otherwise it runs {@link InitBackupRepositoryUseCase}, which treats an
     * already-existing repository as a successful, idempotent init.
     */
    @PostMapping("/backup-repositories/{name}/provision/init")
    public ResponseEntity<ProvisionInitResponse> provisionInit(@PathVariable String name) {
        if (findRepository(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<BackupJob> host = firstJobTargeting(name);
        if (host.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ProvisionInitResponse(false, false,
                "No backup job references this repository, so there is no host to initialise it from"));
        }
        log.info("Initialising backup repository {} from job {}",
            LogSafe.forLog(name), LogSafe.forLog(host.get().name()));
        RepoInitResult result = initBackupRepository.initRepo(name, host.get().machineId());
        return ResponseEntity.ok(ProvisionInitResponse.from(result));
    }

    /**
     * Prepare a job's client host by installing borg on it (the fix for the {@code exit 127} / {@code borg:
     * not found} run failure). The panel acts from a job's readiness view and the job knows its machine, so
     * this is job-scoped: it resolves {@code job.machineId()} and runs {@link PrepareBackupClientUseCase}.
     * {@code 404} when the job is unknown; otherwise {@code 200} with the outcome — {@code started} (poll the
     * status endpoint) or {@code scriptOnly} (run the staged {@code sudo bash <path>}). Never fails opaquely.
     */
    @PostMapping("/backup-jobs/{machineId}/prepare-client")
    public ResponseEntity<PrepareClientResponse> prepareClient(@PathVariable String machineId) {
        Optional<BackupJob> job = findJob(machineId);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Preparing the backup client on machine {}", LogSafe.forLog(machineId));
        PrepareResult result = prepareBackupClient.prepareClient(job.get().machineId());
        return ResponseEntity.ok(PrepareClientResponse.from(result));
    }

    /**
     * Accept <b>Back up as root</b> for a machine (#334) — the single action behind the machine's
     * {@code BACK_UP_AS_ROOT} nudge and its Advanced checkbox.
     *
     * <p>It replaces a two-step dance nobody could discover: {@code PUT /backup-jobs/{machineId}} flipped the
     * flag and {@code POST …/prepare-client} installed the sudoers grant, and doing only the first produced a
     * job whose every run died on {@code sudo -n} before borg started. One call now does both, and the
     * response is compound on purpose: {@code granted} says whether the machine really can run borg as root,
     * {@code job} carries the flag as it now stands, and {@code provisioning} is non-null exactly when the
     * grant had to be asked for — so "the call succeeded" is never mistaken for "root reads are on tonight".
     *
     * <p>The controller decides none of that. Whether the flag may move is
     * {@link net.vaier.domain.BackupJob#enablingBackupAsRoot}'s call; this maps the outcome onto a response
     * and lets {@link NotFoundException} (nothing on the machine is backed up) become a {@code 404}.
     */
    @PostMapping("/backup-jobs/{machineId}/back-up-as-root")
    public ResponseEntity<BackUpAsRootResponse> backUpAsRoot(@PathVariable String machineId) {
        log.info("Enabling back up as root for machine {}", LogSafe.forLog(machineId));
        BackupAsRootOutcome outcome = enableBackupAsRoot.enableBackupAsRoot(MachineId.of(machineId));
        return ResponseEntity.ok(new BackUpAsRootResponse(outcome.granted(),
            JobResponse.from(outcome.job(), machineNameOf(outcome.job().machineId())),
            outcome.readying() == null ? null : ProvisioningResponse.from(outcome.readying())));
    }

    /**
     * The {@code POST /backup-jobs/{machineId}/back-up-as-root} response. {@code granted} is whether the
     * machine can run borg as root right now (probed, never assumed); {@code job} is the job as it now
     * stands, so the browser reads the flag rather than guessing it from a 200; {@code provisioning} carries
     * the grant install Vaier launched or the script it staged, and is {@code null} when nothing needed
     * installing. Three fields because there are three different things to say, and collapsing them would
     * let a UI tell an operator their data is covered when it is not.
     */
    record BackUpAsRootResponse(boolean granted, JobResponse job, ProvisioningResponse provisioning) {}

    /**
     * Report the progress of a launched client-prepare. The install is detached (an apt/dnf install can
     * exceed the exec cap), so {@code POST …/prepare-client} returns once it has started and the UI polls
     * this for the outcome: {@code RUNNING} until the install settles, then {@code SUCCESS}/{@code FAILED}
     * with a log tail. {@code 404} when the job is unknown.
     */
    @GetMapping("/backup-jobs/{machineId}/prepare-client/status")
    public ResponseEntity<PrepareClientStatusResponse> prepareClientStatus(@PathVariable String machineId) {
        Optional<BackupJob> job = findJob(machineId);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PrepareStatus status = prepareBackupClient.prepareClientStatus(job.get().machineId());
        return ResponseEntity.ok(PrepareClientStatusResponse.from(status));
    }

    /** A machine to provision from: a first enabled job targeting the repo, else any job that targets it. */
    private Optional<BackupJob> firstJobTargeting(String repositoryName) {
        List<BackupJob> all = getBackupJobs.getBackupJobs();
        return all.stream()
            .filter(j -> j.repositoryName().equals(repositoryName) && j.enabled())
            .findFirst()
            .or(() -> all.stream().filter(j -> j.repositoryName().equals(repositoryName)).findFirst());
    }

    /** The job backing up this machine, or empty when it has none. A machine has one job; that keys it. */
    private Optional<BackupJob> findJob(String machineId) {
        MachineId id = MachineId.of(machineId);
        return getBackupJobs.getBackupJobs().stream()
            .filter(j -> j.machineId().equals(id)).findFirst();
    }

    private java.util.Optional<BackupRepository> findRepository(String name) {
        return getBackupRepositories.getBackupRepositories().stream()
            .filter(r -> r.name().equals(name)).findFirst();
    }

    private Optional<BackupServer> findServer(String serverName) {
        return getBackupServers.getBackupServers().stream()
            .filter(s -> s.name().equals(serverName)).findFirst();
    }

    /**
     * The repository view for the browser, with {@code repoPath} resolved to the <em>effective</em> path so
     * the UI can show where the store actually points. When the repository's server is known the path is
     * derived through it ({@link BackupRepository#repoPathOn}); when the server is unknown the raw stored
     * override (which may be null) is returned rather than failing.
     */
    private ServerResponse toResponse(BackupServer s) {
        return ServerResponse.from(s, machineNameOf(s.machineId()));
    }

    private RepositoryResponse toResponse(BackupRepository r, List<Machine> fleet, List<BackupJob> jobs) {
        String effectivePath = findServer(r.serverName())
            .map(r::repoPathOn)
            .orElse(r.repoPath());
        boolean hasPassphrase = r.passphrase() != null && !r.passphrase().isBlank();
        return new RepositoryResponse(r.name(), storeLabel(r, fleet, jobs), r.serverName(), effectivePath,
            r.appendOnly(), hasPassphrase);
    }

    /**
     * What to call this store where a person reads it. A repository is named after the machine's identity,
     * so its own name says nothing — and the browser must not work the label out for itself, or two
     * surfaces end up disagreeing about which store is which, which is the failure the label exists to
     * prevent. {@link BackupStoreLabel} owns the rule; this only finds the machine to ask it about.
     */
    private String storeLabel(BackupRepository repository, List<Machine> fleet, List<BackupJob> jobs) {
        return jobs.stream()
            .filter(j -> repository.name().equals(j.repositoryName()))
            .findFirst()
            .flatMap(job -> fleet.stream().filter(m -> m.id().equals(job.machineId())).findFirst())
            .map(machine -> BackupStoreLabel.of(machine, fleet))
            // No job claims it, so its machine has left the fleet — or never had one. The name IS an
            // identity, so say that plainly rather than showing a bare UUID an operator cannot place.
            .orElseGet(() -> Machine.labelFor(machineIdOrNull(repository.name()), Optional.empty()));
    }

    /** The repository's name read as an identity, or null when it predates identity-named repositories. */
    private static MachineId machineIdOrNull(String repositoryName) {
        try {
            return MachineId.of(repositoryName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- DTOs ---

    /**
     * Create/update a backup server (the name is the path variable). A null {@code sshPort} defaults to the
     * borg-server convention. The server carries no secret, so every field round-trips.
     */
    /** {@code machineId} identifies an existing machine; an unknown one is a 404, never a dangling record. */
    record ServerRequest(String machineId, String host, Integer sshPort, String borgUser,
                         String baseRepoPath, String serverDataPath, boolean managed) {}

    /**
     * The backup server as returned to the browser (servers hold no secrets).
     *
     * <p>It carries both {@code machineId} — what the store actually keys on — and {@code machineName},
     * resolved for display and {@code null} when the machine has left the fleet. The browser still speaks
     * names; the id is here so it can stop.
     */
    record ServerResponse(String name, String machineId, String machineName, String host, int sshPort,
                          String borgUser, String baseRepoPath, String serverDataPath, boolean managed) {
        static ServerResponse from(BackupServer s, String machineName) {
            return new ServerResponse(s.name(), s.machineId().value(), machineName, s.host(), s.sshPort(),
                s.borgUser(), s.baseRepoPath(), s.serverDataPath(), s.managed());
        }
    }

    /**
     * The outcome of a provision attempt for the UI (never carries a secret). {@code stagedScriptPath} is the
     * absolute on-host path Vaier wrote the setup script to when it could SSH the machine but not drive its
     * docker, so the UI renders {@code sudo bash <path>} precisely; it is {@code null} on every other path.
     */
    record ProvisionServerResponse(boolean provisioned, boolean scriptOnly, boolean started, String message,
                                   String stagedScriptPath) {
        static ProvisionServerResponse from(ProvisionResult r) {
            return new ProvisionServerResponse(r.provisioned(), r.scriptOnly(), r.started(), r.message(),
                r.stagedScriptPath());
        }
    }

    /** The progress of a launched provision for the UI: {@code RUNNING}/{@code SUCCESS}/{@code FAILED} + a log tail. */
    record ProvisionStatusResponse(String state, String logTail) {
        static ProvisionStatusResponse from(ProvisionStatus s) {
            return new ProvisionStatusResponse(s.state().name(), s.logTail());
        }
    }

    /**
     * The outcome of a prepare-client attempt for the UI (never carries a secret). Mirrors
     * {@link ProvisionServerResponse}: {@code stagedScriptPath} is the absolute on-host path Vaier wrote the
     * install script to when it could SSH the host but not gain root, so the UI renders {@code sudo bash
     * <path>} precisely; it is {@code null} on every other path.
     */
    record PrepareClientResponse(boolean prepared, boolean scriptOnly, boolean started, String message,
                                 String stagedScriptPath) {
        static PrepareClientResponse from(PrepareResult r) {
            return new PrepareClientResponse(r.prepared(), r.scriptOnly(), r.started(), r.message(),
                r.stagedScriptPath());
        }
    }

    /** The progress of a launched client-prepare: {@code RUNNING}/{@code SUCCESS}/{@code FAILED} + a log tail. */
    record PrepareClientStatusResponse(String state, String logTail) {
        static PrepareClientStatusResponse from(PrepareStatus s) {
            return new PrepareClientStatusResponse(s.state().name(), s.logTail());
        }
    }

    /**
     * The outcome of an authorize-client attempt for the UI (carries no secret — a public key is not one).
     * {@code hostKeyPinned} reports whether Vaier could pin the server's host key on the client (Slice 8), so
     * the UI can warn when it could not (an adopted server that never ran the setup script).
     */
    record AuthorizeResponse(boolean authorized, boolean alreadyTrusted, boolean hostKeyPinned, String message) {
        static AuthorizeResponse from(AuthorizeResult r) {
            return new AuthorizeResponse(r.authorized(), r.alreadyTrusted(), r.hostKeyPinned(), r.message());
        }
    }

    /**
     * Create/update a backup repository (the name is the path variable). {@code repoPath} is an optional
     * override — omit it to derive the path from the server. A blank/omitted {@code passphrase} keeps the
     * stored secret.
     */
    record RepositoryRequest(String serverName, String repoPath, String passphrase, boolean appendOnly) {}

    /**
     * The repository as returned to the browser — reports the <em>effective</em> repo path and only whether
     * a passphrase is held (never the secret itself).
     */
    /**
     * @param name  the repository's own name — the machine's identity, and the directory on the server
     * @param label what to call it where a person reads it ({@link BackupStoreLabel})
     */
    record RepositoryResponse(String name, String label, String serverName, String repoPath,
                              boolean appendOnly, boolean hasPassphrase) {}

    /** The paths an operator selected to start or stop backing up, from the Explorer's "Back up" action. */
    record ProtectPathsRequest(List<String> paths) {}

    /**
     * The {@code POST /machines/{machineId}/backup/paths} response: the updated job (the same fields
     * {@link JobResponse} carries) plus a nullable {@code provisioning} object. The provisioning object is
     * populated only on a machine's FIRST back-up — when the job was newly created and Vaier readied the host
     * automatically — and is {@code null} when the job already existed (adding paths never re-provisions).
     */
    record ProtectPathsResponse(String name, String machineId, String machineName, String repositoryName,
                                List<String> sourcePaths,
                                List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                                String compression, boolean enabled, boolean backupAsRoot,
                                ProvisioningResponse provisioning) {
        static ProtectPathsResponse from(BackupJob j, String machineName, ProvisioningResponse provisioning) {
            return new ProtectPathsResponse(j.name(), j.machineId().value(), machineName, j.repositoryName(),
                j.sourcePaths(),
                j.excludes(), j.keepDaily(), j.keepWeekly(), j.keepMonthly(), j.compression(), j.enabled(),
                j.backupAsRoot(), provisioning);
        }
    }

    /**
     * The outcome of first-back-up auto-provisioning, surfaced on the POST response (never a secret):
     * {@code started} when the borg-client install launched (detached — the frontend watches the
     * {@code prepare-client-settled} SSE event); {@code scriptOnly} when Vaier could not run it itself and the
     * operator must run the staged script; {@code stagedScriptPath} the absolute on-host path for that case
     * (else {@code null}); {@code message} a human-readable reason for any path.
     */
    record ProvisioningResponse(boolean started, boolean scriptOnly, String stagedScriptPath, String message) {
        static ProvisioningResponse from(ReadyingOutcome r) {
            return new ProvisioningResponse(r.started(), r.scriptOnly(), r.stagedScriptPath(), r.message());
        }
    }

    /**
     * The {@code DELETE /machines/{machineId}/backup/paths} response: whether anything actually stopped being
     * backed up, which of the requested paths did, and the job as it now stands ({@code null} when the machine
     * has no job at all — the job-was-deleted case answers {@code 204} instead and has no body).
     *
     * <p>{@code changed} exists because "the call succeeded" and "your data stopped being backed up" are not
     * the same statement, and the browser must never turn the first into the second.
     */
    record UnprotectPathsResponse(boolean changed, List<String> stopped, JobResponse job) {
        static UnprotectPathsResponse from(Unprotection outcome, String machineName) {
            return new UnprotectPathsResponse(outcome.changed(), outcome.stopped(),
                outcome.job() == null ? null : JobResponse.from(outcome.job(), machineName));
        }
    }

    /** Create/update a backup job. */
    /** No {@code machineId}: the path already says which machine's job this is, and two places to say it
     *  is two places to disagree. */
    record JobRequest(String repositoryName, List<String> sourcePaths,
                      List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                      String compression, boolean enabled, boolean backupAsRoot) {}

    /** The job as returned to the browser (jobs hold no secrets). */
    /**
     * A job as returned to the browser. {@code lastRunStatus} is the name of its latest
     * {@link BackupRunStatus}, or {@code null} when the job has never run — null is its own fact and must
     * stay distinguishable from an outcome, since "no run yet" is not success and a tree that coloured it
     * green would be lying about untested data.
     */
    record JobResponse(String name, String machineId, String machineName, String repositoryName,
                       List<String> sourcePaths,
                       List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                       String compression, boolean enabled, boolean backupAsRoot, String lastRunStatus) {
        static JobResponse from(BackupJob j, String machineName) {
            return from(j, machineName, null);
        }

        static JobResponse from(BackupJob j, String machineName, String lastRunStatus) {
            return new JobResponse(j.name(), j.machineId().value(), machineName, j.repositoryName(),
                j.sourcePaths(),
                j.excludes(), j.keepDaily(), j.keepWeekly(), j.keepMonthly(), j.compression(), j.enabled(),
                j.backupAsRoot(), lastRunStatus);
        }
    }

    /** One archive in a repository as returned to the browser. */
    record ArchiveResponse(String name, String id, Instant time) {
        static ArchiveResponse from(Archive a) {
            return new ArchiveResponse(a.name(), a.id(), a.time());
        }
    }

    /**
     * A job's provisioning readiness for the wizard. Beyond whether borg is present on the host and new
     * enough ({@code borgInstalled}/{@code borgVersion}/{@code borgSupported}) and whether the host can
     * reach the NAS borg port ({@code nasReachable}), it carries the checks that kill the false all-green:
     * {@code borgAuthOk} (the client's key is actually trusted on the server), {@code serverBorgVersion}
     * (null when it could not be read) and {@code versionsCompatible} (client and server borg majors match).
     * A host can show {@code borgInstalled} and {@code nasReachable} true yet {@code borgAuthOk} false — so
     * the response never reads as ready on auth alone.
     */
    record ProvisionCheckResponse(boolean borgInstalled, String borgVersion, boolean borgSupported,
                                  boolean nasReachable, boolean borgAuthOk, String serverBorgVersion,
                                  boolean versionsCompatible, boolean backupAsRoot, boolean rootBorgOk) {
        static ProvisionCheckResponse from(BorgAvailability borg, RepoReachability nas, ServerBorgAuth auth,
                                           boolean backupAsRoot, boolean rootBorgOk) {
            String version = borg.version().map(ProvisionCheckResponse::render).orElse(null);
            String serverVersion = auth.serverVersion().map(ProvisionCheckResponse::render).orElse(null);
            return new ProvisionCheckResponse(borg.installed(), version, borg.supported(), nas.reachable(),
                auth.authOk(), serverVersion, auth.versionsCompatible(), backupAsRoot, rootBorgOk);
        }

        private static String render(BorgVersion v) {
            return v.major() + "." + v.minor() + "." + v.patch();
        }
    }

    /** The outcome of a repository init for the wizard (never carries the secret). */
    record ProvisionInitResponse(boolean initialized, boolean alreadyExisted, String message) {
        static ProvisionInitResponse from(RepoInitResult result) {
            return new ProvisionInitResponse(result.initialized(), result.alreadyExisted(), result.message());
        }
    }

    /** One backup run as returned to the browser (runs hold no secrets). */
    /**
     * A run as the UI sees it. {@code summary} is borg's raw output; {@code diagnostics} is the entity's
     * verdict on which of it is worth showing a human (the skipped-file and error lines, without borg's
     * JSON stats object) — empty on a clean run, which is how the UI knows to offer no disclosure.
     */
    /**
     * A run as returned to the browser. {@code needsClientReadying} is the domain's verdict, not the
     * browser's: the shell offers "Get this machine ready" from this flag rather than pattern-matching the
     * summary, so the rule stays in {@link BackupRun} and cannot break the day the wording changes.
     */
    record RunResponse(String runId, String jobName, String machineId, String machineName,
                       String repositoryName,
                       BackupRunStatus status, Instant startedAt, Instant finishedAt, Integer exitCode,
                       String archiveName, String summary, String diagnostics,
                       boolean needsClientReadying) {
        static RunResponse from(BackupRun r, String machineName) {
            return new RunResponse(r.runId(), r.jobName(), r.machineId().value(), machineName,
                r.repositoryName(),
                r.status(), r.startedAt(), r.finishedAt(), r.exitCode(), r.archiveName(), r.summary(),
                r.diagnostics(), r.needsClientReadying());
        }
    }

    /**
     * Write the fleet's survival kit and put copies where Vaier decided they should go.
     *
     * <p>One endpoint for one operator decision — <em>make sure I can still read my backups if this server is
     * gone</em>. The response carries Vaier's reasoning as well as the outcome: which hosts hold a copy and
     * why, which were passed over and why, and which refused. {@code 409} when no kit passphrase has been
     * chosen yet, which is a precondition the operator can fix, not a server error.
     */
    @PostMapping("/survival-kit")
    public ResponseEntity<SurvivalKitResponse> writeSurvivalKit() {
        try {
            return ResponseEntity.ok(SurvivalKitResponse.from(writeSurvivalKit.writeSurvivalKit()));
        } catch (IllegalStateException e) {
            log.warn("Survival kit not written: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /** A machine that now holds a copy of the kit, and the reason Vaier chose it. */
    record KitCopyResponse(String machineId, String machineName, String site, String reason) {}

    /** A machine that holds no copy, and the fact the operator would have to disagree with. */
    record KitSkippedResponse(String machineName, String reason) {}

    /** A destination that would not take the kit, in the words the machine used. */
    record KitFailureResponse(String machineName, String reason) {}

    /**
     * What one write achieved. {@code survivesLossOfVaier} is the answer to the only question that matters —
     * false when no fleet machine took a copy, which puts the fleet back inside the circle the kit exists to
     * break, however well the Vaier server's own copy went.
     */
    record SurvivalKitResponse(int copiesKept, boolean survivesLossOfVaier, boolean fewerCopiesThanIntended,
                               List<KitCopyResponse> chosen, List<KitSkippedResponse> skipped,
                               List<KitFailureResponse> failures) {
        static SurvivalKitResponse from(SurvivalKitReport report) {
            return new SurvivalKitResponse(
                report.rollout().copiesKept(),
                report.rollout().survivesLossOfVaier(),
                report.selection().fewerCopiesThanIntended(),
                report.selection().chosen().stream()
                    .map(p -> new KitCopyResponse(p.machineId().value(), p.machineName(), p.site(), p.reason()))
                    .toList(),
                report.selection().skipped().stream()
                    .map(sk -> new KitSkippedResponse(sk.machineName(), sk.reason()))
                    .toList(),
                report.rollout().failures().stream()
                    .map(f -> new KitFailureResponse(f.machineName(), f.reason()))
                    .toList());
        }
    }
}
