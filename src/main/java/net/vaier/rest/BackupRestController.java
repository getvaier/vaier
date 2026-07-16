package net.vaier.rest;

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
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.SaveBackupJobUseCase;
import net.vaier.application.SaveBackupRepositoryUseCase;
import net.vaier.application.SaveBackupServerUseCase;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgVersion;
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
 * {@link BackupJob} specs. The whole controller is Enterprise-gated with {@link RequiresEnterprise},
 * so a Community instance gets {@code 402 Payment Required} from {@link EnterpriseLicenseInterceptor}
 * and never reaches a handler (precedent: {@code LanScannerRestController}).
 *
 * <p>The repository response never carries the passphrase: it reports only {@code hasPassphrase}
 * (mirrors {@code HostCredentialRestController}'s redacted view). On a PUT edit, a blank/omitted
 * passphrase keeps the stored secret rather than clearing it. Bad input surfaces as
 * {@link IllegalArgumentException} (from the domain records or {@code BackupService}) → {@code 400}
 * via {@link GlobalExceptionHandler}. Every user-supplied name passes through {@link LogSafe#forLog}.
 *
 * <p>Beyond CRUD, a job can be run on demand — {@code POST /backup-jobs/{name}/runs} loads the job and
 * its repository, triggers the run through {@link RunBackupJobUseCase} (the controller reaches the
 * rest-layer runner only through that use-case seam, as every controller does) and returns
 * {@code 202 Accepted} with the {@code RUNNING} run — and its latest outcome read back with
 * {@code GET /backup-jobs/{name}/runs}. Archive listing and provisioning arrive in later slices.
 */
@RestController
@RequiresEnterprise
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

    public BackupRestController(SaveBackupRepositoryUseCase saveBackupRepository,
                               GetBackupRepositoriesUseCase getBackupRepositories,
                               DeleteBackupRepositoryUseCase deleteBackupRepository,
                               GetBackupServersUseCase getBackupServers,
                               SaveBackupServerUseCase saveBackupServer,
                               DeleteBackupServerUseCase deleteBackupServer,
                               GenerateBackupServerSetupScriptUseCase generateBackupServerSetupScript,
                               ProvisionBackupServerUseCase provisionBackupServer,
                               SaveBackupJobUseCase saveBackupJob,
                               GetBackupJobsUseCase getBackupJobs,
                               DeleteBackupJobUseCase deleteBackupJob,
                               GetBackupRunsUseCase getBackupRuns,
                               RunBackupJobUseCase runBackupJob,
                               ListArchivesUseCase listArchivesUseCase,
                               CheckBackupPrerequisitesUseCase checkBackupPrerequisites,
                               InitBackupRepositoryUseCase initBackupRepository,
                               GetMachinesUseCase getMachines,
                               AuthorizeBackupClientUseCase authorizeBackupClient,
                               PrepareBackupClientUseCase prepareBackupClient,
                               ProtectMachinePathsUseCase protectMachinePaths,
                               ForSubscribingToEvents forSubscribingToEvents) {
        this.saveBackupRepository = saveBackupRepository;
        this.getBackupRepositories = getBackupRepositories;
        this.deleteBackupRepository = deleteBackupRepository;
        this.getBackupServers = getBackupServers;
        this.saveBackupServer = saveBackupServer;
        this.deleteBackupServer = deleteBackupServer;
        this.generateBackupServerSetupScript = generateBackupServerSetupScript;
        this.provisionBackupServer = provisionBackupServer;
        this.saveBackupJob = saveBackupJob;
        this.getBackupJobs = getBackupJobs;
        this.deleteBackupJob = deleteBackupJob;
        this.getBackupRuns = getBackupRuns;
        this.runBackupJob = runBackupJob;
        this.listArchivesUseCase = listArchivesUseCase;
        this.checkBackupPrerequisites = checkBackupPrerequisites;
        this.initBackupRepository = initBackupRepository;
        this.getMachines = getMachines;
        this.authorizeBackupClient = authorizeBackupClient;
        this.prepareBackupClient = prepareBackupClient;
        this.protectMachinePaths = protectMachinePaths;
        this.forSubscribingToEvents = forSubscribingToEvents;
    }

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
            .map(ServerResponse::from).toList());
    }

    @GetMapping("/backup-servers/{name}")
    public ResponseEntity<ServerResponse> getServer(@PathVariable String name) {
        return findServer(name)
            .map(server -> ResponseEntity.ok(ServerResponse.from(server)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/backup-servers/{name}")
    public ResponseEntity<ServerResponse> saveServer(@PathVariable String name,
                                                     @RequestBody ServerRequest request) {
        log.info("Saving backup server {}", LogSafe.forLog(name));
        // A null sshPort defaults to the borg-server convention; bad input surfaces as a 400 from the record.
        int sshPort = request.sshPort() != null ? request.sshPort() : BackupServer.DEFAULT_SSH_PORT;
        BackupServer server = new BackupServer(name, request.machineName(), request.host(), sshPort,
            request.borgUser(), request.baseRepoPath(), request.serverDataPath(), request.managed());
        saveBackupServer.saveBackupServer(server);
        return ResponseEntity.ok(ServerResponse.from(server));
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
    @PostMapping("/backup-servers/{name}/authorize/{machineName}")
    public ResponseEntity<AuthorizeResponse> authorizeClient(@PathVariable String name,
                                                             @PathVariable String machineName) {
        if (findServer(name).isEmpty() || !machineExists(machineName)) {
            return ResponseEntity.notFound().build();
        }
        log.info("Authorizing backup client {} on server {}",
            LogSafe.forLog(machineName), LogSafe.forLog(name));
        AuthorizeResult result = authorizeBackupClient.authorizeClient(name, machineName);
        return ResponseEntity.ok(AuthorizeResponse.from(result));
    }

    private boolean machineExists(String machineName) {
        return getMachines.getAllMachines().stream().anyMatch(m -> m.name().equals(machineName));
    }

    // --- Backup repositories ---

    @GetMapping("/backup-repositories")
    public ResponseEntity<List<RepositoryResponse>> listRepositories() {
        return ResponseEntity.ok(getBackupRepositories.getBackupRepositories().stream()
            .map(this::toResponse).toList());
    }

    @GetMapping("/backup-repositories/{name}")
    public ResponseEntity<RepositoryResponse> getRepository(@PathVariable String name) {
        return findRepository(name)
            .map(repo -> ResponseEntity.ok(toResponse(repo)))
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
        return ResponseEntity.ok(toResponse(repository));
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

    @GetMapping("/backup-jobs")
    public ResponseEntity<List<JobResponse>> listJobs() {
        return ResponseEntity.ok(getBackupJobs.getBackupJobs().stream().map(JobResponse::from).toList());
    }

    @GetMapping("/backup-jobs/{name}")
    public ResponseEntity<JobResponse> getJob(@PathVariable String name) {
        return getBackupJobs.getBackupJobs().stream()
            .filter(j -> j.name().equals(name)).findFirst()
            .map(job -> ResponseEntity.ok(JobResponse.from(job)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/backup-jobs/{name}")
    public ResponseEntity<JobResponse> saveJob(@PathVariable String name,
                                               @RequestBody JobRequest request) {
        log.info("Saving backup job {}", LogSafe.forLog(name));
        BackupJob job = new BackupJob(name, request.machineName(), request.repositoryName(),
            request.sourcePaths(), request.excludes(),
            request.keepDaily(), request.keepWeekly(), request.keepMonthly(),
            request.compression(), request.enabled(), request.backupAsRoot());
        saveBackupJob.saveBackupJob(job);
        return ResponseEntity.ok(JobResponse.from(job));
    }

    @DeleteMapping("/backup-jobs/{name}")
    public ResponseEntity<Void> deleteJob(@PathVariable String name) {
        log.info("Deleting backup job {}", LogSafe.forLog(name));
        deleteBackupJob.deleteBackupJob(name);
        return ResponseEntity.noContent().build();
    }

    // --- Just select and back up (protected paths) ---

    /**
     * Back up a selection of paths on a machine — the Explorer's "select files, click Back up" flow. All the
     * machinery hides behind this one call: get-or-create the machine's repository (with a backend-generated
     * passphrase) and its job, then add the posted paths to the job's protected set, normalized so no path is
     * a descendant of another. {@code 404} when the machine is unknown; {@code 409} (via
     * {@link net.vaier.domain.ConflictException}) when no backup server has been designated yet. Otherwise
     * {@code 200} with the updated job — the same shape {@code GET /backup-jobs} returns.
     */
    @PostMapping("/machines/{machine}/backup/paths")
    public ResponseEntity<JobResponse> protectPaths(@PathVariable String machine,
                                                    @RequestBody ProtectPathsRequest request) {
        if (!machineExists(machine)) {
            return ResponseEntity.notFound().build();
        }
        log.info("Backing up {} paths on machine {}",
            request.paths() == null ? 0 : request.paths().size(), LogSafe.forLog(machine));
        BackupJob job = protectMachinePaths.protect(machine, request.paths());
        return ResponseEntity.ok(JobResponse.from(job));
    }

    /**
     * Stop backing up a selection of paths on a machine. Removing a path also removes any protected path that
     * is a descendant of it. {@code 404} when the machine is unknown; a machine with no job is a no-op success.
     * When the last protected path is removed the job is deleted (leaving the repository intact) and the
     * response is {@code 204 No Content}; otherwise {@code 200} with the updated job.
     */
    @DeleteMapping("/machines/{machine}/backup/paths")
    public ResponseEntity<JobResponse> unprotectPaths(@PathVariable String machine,
                                                      @RequestBody ProtectPathsRequest request) {
        if (!machineExists(machine)) {
            return ResponseEntity.<JobResponse>notFound().build();
        }
        log.info("Stopping backup of {} paths on machine {}",
            request.paths() == null ? 0 : request.paths().size(), LogSafe.forLog(machine));
        return protectMachinePaths.unprotect(machine, request.paths())
            .map(job -> ResponseEntity.ok(JobResponse.from(job)))
            .orElseGet(() -> ResponseEntity.<JobResponse>noContent().build());
    }

    // --- Backup runs ---

    /**
     * Trigger an on-demand run of the named job. Resolves the job and its repository (404 when either is
     * unknown), launches the run through {@link RunBackupJobUseCase} and returns {@code 202 Accepted} with
     * the {@code RUNNING} run — a poll settles it to its outcome later.
     */
    @PostMapping("/backup-jobs/{name}/runs")
    public ResponseEntity<RunResponse> runJob(@PathVariable String name) {
        log.info("Running backup job {} on demand", LogSafe.forLog(name));
        Optional<BackupJob> job = findJob(name);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<BackupRepository> repo = findRepository(job.get().repositoryName());
        if (repo.isEmpty() || findServer(repo.get().serverName()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        BackupRun run = runBackupJob.runJob(job.get(), repo.get());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunResponse.from(run));
    }

    /** The latest run for the named job, or {@code 404} when it has never run (matches the CRUD lookups). */
    @GetMapping("/backup-jobs/{name}/runs")
    public ResponseEntity<RunResponse> getRuns(@PathVariable String name) {
        return getBackupRuns.latestForJob(name)
            .map(run -> ResponseEntity.ok(RunResponse.from(run)))
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
    @GetMapping("/backup-jobs/{name}/provision/check")
    public ResponseEntity<ProvisionCheckResponse> provisionCheck(@PathVariable String name) {
        Optional<BackupJob> job = findJob(name);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<BackupRepository> repo = findRepository(job.get().repositoryName());
        if (repo.isEmpty() || findServer(repo.get().serverName()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Checking backup prerequisites for job {}", LogSafe.forLog(name));
        BorgAvailability borg = checkBackupPrerequisites.checkBorg(job.get().machineName());
        RepoReachability nas = checkBackupPrerequisites.checkNas(job.get().repositoryName(),
            job.get().machineName());
        // The decisive probe: authenticate to the server and compare versions, threading the client's own
        // borg version in so compatibility is judged against a real server version — not assumed.
        ServerBorgAuth auth = checkBackupPrerequisites.checkServerAuth(job.get().repositoryName(),
            job.get().machineName(), borg.version());
        // "Back up as root" is only a prerequisite for a job that asked for it: a job with the toggle off does
        // not need the sudo grant and must never be shown as failing a check it will never use — so it is not
        // even probed (no pointless SSH round trip).
        boolean backupAsRoot = job.get().backupAsRoot();
        boolean rootBorgOk = backupAsRoot
            && checkBackupPrerequisites.checkRootBorg(job.get().machineName()).canRunAsRoot();
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
        RepoInitResult result = initBackupRepository.initRepo(name, host.get().machineName());
        return ResponseEntity.ok(ProvisionInitResponse.from(result));
    }

    /**
     * Prepare a job's client host by installing borg on it (the fix for the {@code exit 127} / {@code borg:
     * not found} run failure). The panel acts from a job's readiness view and the job knows its machine, so
     * this is job-scoped: it resolves {@code job.machineName()} and runs {@link PrepareBackupClientUseCase}.
     * {@code 404} when the job is unknown; otherwise {@code 200} with the outcome — {@code started} (poll the
     * status endpoint) or {@code scriptOnly} (run the staged {@code sudo bash <path>}). Never fails opaquely.
     */
    @PostMapping("/backup-jobs/{name}/prepare-client")
    public ResponseEntity<PrepareClientResponse> prepareClient(@PathVariable String name) {
        Optional<BackupJob> job = findJob(name);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        log.info("Preparing backup client for job {}", LogSafe.forLog(name));
        PrepareResult result = prepareBackupClient.prepareClient(job.get().machineName());
        return ResponseEntity.ok(PrepareClientResponse.from(result));
    }

    /**
     * Report the progress of a launched client-prepare. The install is detached (an apt/dnf install can
     * exceed the exec cap), so {@code POST …/prepare-client} returns once it has started and the UI polls
     * this for the outcome: {@code RUNNING} until the install settles, then {@code SUCCESS}/{@code FAILED}
     * with a log tail. {@code 404} when the job is unknown.
     */
    @GetMapping("/backup-jobs/{name}/prepare-client/status")
    public ResponseEntity<PrepareClientStatusResponse> prepareClientStatus(@PathVariable String name) {
        Optional<BackupJob> job = findJob(name);
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PrepareStatus status = prepareBackupClient.prepareClientStatus(job.get().machineName());
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

    private Optional<BackupJob> findJob(String name) {
        return getBackupJobs.getBackupJobs().stream()
            .filter(j -> j.name().equals(name)).findFirst();
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
    private RepositoryResponse toResponse(BackupRepository r) {
        String effectivePath = findServer(r.serverName())
            .map(r::repoPathOn)
            .orElse(r.repoPath());
        boolean hasPassphrase = r.passphrase() != null && !r.passphrase().isBlank();
        return new RepositoryResponse(r.name(), r.serverName(), effectivePath, r.appendOnly(), hasPassphrase);
    }

    // --- DTOs ---

    /**
     * Create/update a backup server (the name is the path variable). A null {@code sshPort} defaults to the
     * borg-server convention. The server carries no secret, so every field round-trips.
     */
    record ServerRequest(String machineName, String host, Integer sshPort, String borgUser,
                         String baseRepoPath, String serverDataPath, boolean managed) {}

    /** The backup server as returned to the browser (servers hold no secrets). */
    record ServerResponse(String name, String machineName, String host, int sshPort, String borgUser,
                          String baseRepoPath, String serverDataPath, boolean managed) {
        static ServerResponse from(BackupServer s) {
            return new ServerResponse(s.name(), s.machineName(), s.host(), s.sshPort(), s.borgUser(),
                s.baseRepoPath(), s.serverDataPath(), s.managed());
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
    record RepositoryResponse(String name, String serverName, String repoPath,
                              boolean appendOnly, boolean hasPassphrase) {}

    /** The paths an operator selected to start or stop backing up, from the Explorer's "Back up" action. */
    record ProtectPathsRequest(List<String> paths) {}

    /** Create/update a backup job. */
    record JobRequest(String machineName, String repositoryName, List<String> sourcePaths,
                      List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                      String compression, boolean enabled, boolean backupAsRoot) {}

    /** The job as returned to the browser (jobs hold no secrets). */
    record JobResponse(String name, String machineName, String repositoryName, List<String> sourcePaths,
                       List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                       String compression, boolean enabled, boolean backupAsRoot) {
        static JobResponse from(BackupJob j) {
            return new JobResponse(j.name(), j.machineName(), j.repositoryName(), j.sourcePaths(),
                j.excludes(), j.keepDaily(), j.keepWeekly(), j.keepMonthly(), j.compression(), j.enabled(),
                j.backupAsRoot());
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
    record RunResponse(String runId, String jobName, String machineName, String repositoryName,
                       BackupRunStatus status, Instant startedAt, Instant finishedAt, Integer exitCode,
                       String archiveName, String summary, String diagnostics) {
        static RunResponse from(BackupRun r) {
            return new RunResponse(r.runId(), r.jobName(), r.machineName(), r.repositoryName(),
                r.status(), r.startedAt(), r.finishedAt(), r.exitCode(), r.archiveName(), r.summary(),
                r.diagnostics());
        }
    }
}
