package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.DeleteBackupJobUseCase;
import net.vaier.application.DeleteBackupRepositoryUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.ListArchivesUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.SaveBackupJobUseCase;
import net.vaier.application.SaveBackupRepositoryUseCase;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final SaveBackupJobUseCase saveBackupJob;
    private final GetBackupJobsUseCase getBackupJobs;
    private final DeleteBackupJobUseCase deleteBackupJob;
    private final GetBackupRunsUseCase getBackupRuns;
    private final RunBackupJobUseCase runBackupJob;
    private final ListArchivesUseCase listArchivesUseCase;
    private final CheckBackupPrerequisitesUseCase checkBackupPrerequisites;
    private final InitBackupRepositoryUseCase initBackupRepository;

    public BackupRestController(SaveBackupRepositoryUseCase saveBackupRepository,
                               GetBackupRepositoriesUseCase getBackupRepositories,
                               DeleteBackupRepositoryUseCase deleteBackupRepository,
                               SaveBackupJobUseCase saveBackupJob,
                               GetBackupJobsUseCase getBackupJobs,
                               DeleteBackupJobUseCase deleteBackupJob,
                               GetBackupRunsUseCase getBackupRuns,
                               RunBackupJobUseCase runBackupJob,
                               ListArchivesUseCase listArchivesUseCase,
                               CheckBackupPrerequisitesUseCase checkBackupPrerequisites,
                               InitBackupRepositoryUseCase initBackupRepository) {
        this.saveBackupRepository = saveBackupRepository;
        this.getBackupRepositories = getBackupRepositories;
        this.deleteBackupRepository = deleteBackupRepository;
        this.saveBackupJob = saveBackupJob;
        this.getBackupJobs = getBackupJobs;
        this.deleteBackupJob = deleteBackupJob;
        this.getBackupRuns = getBackupRuns;
        this.runBackupJob = runBackupJob;
        this.listArchivesUseCase = listArchivesUseCase;
        this.checkBackupPrerequisites = checkBackupPrerequisites;
        this.initBackupRepository = initBackupRepository;
    }

    // --- Backup repositories ---

    @GetMapping("/backup-repositories")
    public ResponseEntity<List<RepositoryResponse>> listRepositories() {
        return ResponseEntity.ok(getBackupRepositories.getBackupRepositories().stream()
            .map(RepositoryResponse::from).toList());
    }

    @GetMapping("/backup-repositories/{name}")
    public ResponseEntity<RepositoryResponse> getRepository(@PathVariable String name) {
        return findRepository(name)
            .map(repo -> ResponseEntity.ok(RepositoryResponse.from(repo)))
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
        int sshPort = request.sshPort() == null ? BackupRepository.DEFAULT_SSH_PORT : request.sshPort();
        String borgUser = (request.borgUser() == null || request.borgUser().isBlank())
            ? BackupRepository.DEFAULT_BORG_USER : request.borgUser();
        BackupRepository repository = new BackupRepository(name, request.nasHost(), sshPort, borgUser,
            request.repoPath(), passphrase, request.appendOnly());
        saveBackupRepository.saveBackupRepository(repository);
        return ResponseEntity.ok(RepositoryResponse.from(repository));
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
            request.compression(), request.enabled());
        saveBackupJob.saveBackupJob(job);
        return ResponseEntity.ok(JobResponse.from(job));
    }

    @DeleteMapping("/backup-jobs/{name}")
    public ResponseEntity<Void> deleteJob(@PathVariable String name) {
        log.info("Deleting backup job {}", LogSafe.forLog(name));
        deleteBackupJob.deleteBackupJob(name);
        return ResponseEntity.noContent().build();
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
        if (repo.isEmpty()) {
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
        log.info("Checking backup prerequisites for job {}", LogSafe.forLog(name));
        BorgAvailability borg = checkBackupPrerequisites.checkBorg(job.get().machineName());
        RepoReachability nas = checkBackupPrerequisites.checkNas(job.get().repositoryName(),
            job.get().machineName());
        return ResponseEntity.ok(ProvisionCheckResponse.from(borg, nas));
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

    // --- DTOs ---

    /** Create/update a backup repository. A blank/omitted {@code passphrase} keeps the stored secret. */
    record RepositoryRequest(String nasHost, Integer sshPort, String borgUser, String repoPath,
                             String passphrase, boolean appendOnly) {}

    /** The repository as returned to the browser — reports only whether a passphrase is held. */
    record RepositoryResponse(String name, String nasHost, int sshPort, String borgUser, String repoPath,
                              boolean appendOnly, boolean hasPassphrase) {
        static RepositoryResponse from(BackupRepository r) {
            boolean hasPassphrase = r.passphrase() != null && !r.passphrase().isBlank();
            return new RepositoryResponse(r.name(), r.nasHost(), r.sshPort(), r.borgUser(), r.repoPath(),
                r.appendOnly(), hasPassphrase);
        }
    }

    /** Create/update a backup job. */
    record JobRequest(String machineName, String repositoryName, List<String> sourcePaths,
                      List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                      String compression, boolean enabled) {}

    /** The job as returned to the browser (jobs hold no secrets). */
    record JobResponse(String name, String machineName, String repositoryName, List<String> sourcePaths,
                       List<String> excludes, int keepDaily, int keepWeekly, int keepMonthly,
                       String compression, boolean enabled) {
        static JobResponse from(BackupJob j) {
            return new JobResponse(j.name(), j.machineName(), j.repositoryName(), j.sourcePaths(),
                j.excludes(), j.keepDaily(), j.keepWeekly(), j.keepMonthly(), j.compression(), j.enabled());
        }
    }

    /** One archive in a repository as returned to the browser. */
    record ArchiveResponse(String name, String id, Instant time) {
        static ArchiveResponse from(Archive a) {
            return new ArchiveResponse(a.name(), a.id(), a.time());
        }
    }

    /**
     * A job's provisioning readiness for the wizard: whether borg is present on the host and new enough,
     * its version string (null when borg is absent), and whether the host can reach the NAS borg port.
     */
    record ProvisionCheckResponse(boolean borgInstalled, String borgVersion, boolean borgSupported,
                                  boolean nasReachable) {
        static ProvisionCheckResponse from(BorgAvailability borg, RepoReachability nas) {
            String version = borg.version()
                .map(v -> v.major() + "." + v.minor() + "." + v.patch())
                .orElse(null);
            return new ProvisionCheckResponse(borg.installed(), version, borg.supported(), nas.reachable());
        }
    }

    /** The outcome of a repository init for the wizard (never carries the secret). */
    record ProvisionInitResponse(boolean initialized, boolean alreadyExisted, String message) {
        static ProvisionInitResponse from(RepoInitResult result) {
            return new ProvisionInitResponse(result.initialized(), result.alreadyExisted(), result.message());
        }
    }

    /** One backup run as returned to the browser (runs hold no secrets). */
    record RunResponse(String runId, String jobName, String machineName, String repositoryName,
                       BackupRunStatus status, Instant startedAt, Instant finishedAt, Integer exitCode,
                       String archiveName, String summary) {
        static RunResponse from(BackupRun r) {
            return new RunResponse(r.runId(), r.jobName(), r.machineName(), r.repositoryName(),
                r.status(), r.startedAt(), r.finishedAt(), r.exitCode(), r.archiveName(), r.summary());
        }
    }
}
