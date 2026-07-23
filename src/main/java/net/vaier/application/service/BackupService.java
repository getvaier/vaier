package net.vaier.application.service;

import net.vaier.application.DeleteBackupJobUseCase;
import net.vaier.application.DeleteBackupRepositoryUseCase;
import net.vaier.application.DeleteBackupServerUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.Passphrases;
import net.vaier.application.ProtectMachinePathsUseCase;
import net.vaier.application.SaveBackupJobUseCase;
import net.vaier.application.SaveBackupRepositoryUseCase;
import net.vaier.application.SaveBackupServerUseCase;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
import net.vaier.domain.ConflictException;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.Unprotection;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForReadyingBackupClients;
import net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome;
import net.vaier.domain.port.ForRecordingBackupRuns;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The single fleet-backup domain service: pure CRUD/query over the backup repository and job stores.
 * It orchestrates only — reading and writing through the two driven ports — and holds no business
 * rules of its own. The one referential rule it enforces on save (a job must name a repository that
 * exists) is the {@link BackupJob}'s own decision, asked via {@link BackupJob#referencesKnownRepository};
 * the service merely supplies the known repositories and turns a "no" into a {@code 400}-mapped
 * {@link IllegalArgumentException}.
 *
 * <p>It depends solely on driven ports and never calls another {@code *UseCase}; the SSH-driven run
 * orchestration lives in {@code rest/BackupRunner}, not here.
 */
@Service
public class BackupService implements
    SaveBackupRepositoryUseCase, GetBackupRepositoriesUseCase, DeleteBackupRepositoryUseCase,
    SaveBackupServerUseCase, GetBackupServersUseCase, DeleteBackupServerUseCase,
    SaveBackupJobUseCase, GetBackupJobsUseCase, DeleteBackupJobUseCase, GetBackupRunsUseCase,
    ProtectMachinePathsUseCase {

    private final ForPersistingBackupRepositories repositories;
    private final ForPersistingBackupServers servers;
    private final ForPersistingBackupJobs jobs;
    private final ForRecordingBackupRuns runs;
    private final ForReadyingBackupClients readier;

    public BackupService(ForPersistingBackupRepositories repositories, ForPersistingBackupServers servers,
                         ForPersistingBackupJobs jobs, ForRecordingBackupRuns runs,
                         ForReadyingBackupClients readier) {
        this.repositories = repositories;
        this.servers = servers;
        this.jobs = jobs;
        this.runs = runs;
        this.readier = readier;
    }

    @Override
    public void saveBackupRepository(BackupRepository repository) {
        repositories.save(repository);
    }

    @Override
    public List<BackupRepository> getBackupRepositories() {
        return repositories.getAll();
    }

    @Override
    public void deleteBackupRepository(String name) {
        repositories.deleteByName(name);
    }

    @Override
    public void saveBackupServer(BackupServer server) {
        servers.getAll().stream()
            .filter(existing -> !existing.name().equals(server.name()))
            .findFirst()
            .ifPresent(existing -> {
                throw new IllegalArgumentException("The fleet already has a backup server: "
                    + existing.name() + ". Remove it before designating another.");
            });
        servers.save(server);
    }

    @Override
    public List<BackupServer> getBackupServers() {
        return servers.getAll();
    }

    @Override
    public void deleteBackupServer(String name) {
        servers.deleteByName(name);
    }

    @Override
    public void saveBackupJob(BackupJob job) {
        if (!job.referencesKnownRepository(repositories.getAll())) {
            throw new IllegalArgumentException("No backup repository named " + job.repositoryName());
        }
        jobs.save(job);
    }

    @Override
    public List<BackupJob> getBackupJobs() {
        return jobs.getAll();
    }

    @Override
    public void deleteBackupJob(String name) {
        jobs.deleteByName(name);
    }

    @Override
    public Optional<BackupRun> latestForJob(String jobName) {
        return runs.latestForJob(jobName);
    }

    /**
     * The just-select-and-back-up entry point: get-or-create the machine's repository and job, then fold the
     * selected {@code paths} into the job's protected {@link SourcePaths}. A repository is created only when
     * the machine has no job yet, and always with a backend-generated passphrase — Vaier never takes a
     * repository secret from a client. Rejects with a {@link ConflictException} (mapped to {@code 409}) when
     * no backup server has been designated, since a repository has nowhere to live without one.
     *
     * <p>On the machine's FIRST back-up (no prior job) the freshly-created job decides its host must be
     * readied and calls the driven {@link ForReadyingBackupClients} port — the operator never runs the
     * provisioning wizard by hand. The service only supplies the port and orchestrates; the decision (first
     * back-up ⇒ ready the host) is the job's, on {@link BackupJob#readyClientHostForFirstBackup}. Readying is
     * detached and never throws, so it can never fail this call; its outcome rides back on the result.
     */
    @Override
    public ProtectionOutcome protect(String machineName, List<String> paths) {
        BackupServer server = theBackupServer();
        String slug = BackupRepository.sanitizedName(machineName);
        Optional<BackupJob> existing = jobs.getByMachine(machineName).stream().findFirst();
        boolean firstBackup = existing.isEmpty();
        BackupRepository repository = existing
            .flatMap(job -> repositories.getByName(job.repositoryName()))
            .orElseGet(() -> createRepository(slug, server));
        BackupJob job = existing
            .map(existingJob -> existingJob.protecting(paths))
            .orElseGet(() -> new BackupJob(slug, machineName, repository.name(),
                SourcePaths.of(List.of()).protecting(paths).paths(), List.of(),
                7, 4, 6, BackupJob.DEFAULT_COMPRESSION, true, false));
        jobs.save(job);
        ReadyingOutcome readying = job.readyClientHostForFirstBackup(firstBackup, readier).orElse(null);
        return new ProtectionOutcome(job, readying);
    }

    /**
     * The inverse: stop backing {@code paths} up on the machine. The service only orchestrates — it loads the
     * machine's job, asks the job what "stop backing these up" means ({@link BackupJob#unprotecting}) and
     * writes the answer: delete when the job's last protected path went (leaving the repository intact), save
     * when it changed, and touch nothing at all when the request matched nothing.
     *
     * <p>Which paths can be dropped from the protected set and which have to become excludes is the job's
     * decision, not this method's; the {@link Unprotection} it returns is passed straight back so the caller
     * can tell the operator what really happened rather than assuming it worked.
     */
    @Override
    public Unprotection unprotect(String machineName, List<String> paths) {
        Optional<BackupJob> existing = jobs.getByMachine(machineName).stream().findFirst();
        if (existing.isEmpty()) {
            return Unprotection.nothingMatched(null);
        }
        Unprotection outcome = existing.get().unprotecting(paths);
        if (outcome.jobDeleted()) {
            jobs.deleteByName(existing.get().name());
        } else if (outcome.changed()) {
            jobs.save(outcome.job());
        }
        return outcome;
    }

    /** The fleet's single backup server, or a {@code 409}-mapped conflict when none is designated yet. */
    private BackupServer theBackupServer() {
        return servers.getAll().stream().findFirst()
            .orElseThrow(() -> new ConflictException(
                "Designate a backup server before backing up machines."));
    }

    /**
     * Get-or-create the repository named {@code name}: reuse the stored one if it already exists, otherwise
     * create and persist it with a fresh backend-generated passphrase.
     *
     * <p><b>Reuse must win over regenerate.</b> A repository's passphrase seals its borg repo on the NAS, and
     * borg has no way to adopt a new one — so minting a fresh passphrase over an <em>existing</em> repository
     * orphans it (borg can no longer decrypt it, and every backup then fails to authenticate). Because the
     * get-or-create in {@link #protect} looks the repository up by the job's {@code repositoryName}, a
     * name/slug mismatch can make that lookup miss for a repository that really exists under the slug; this
     * name-keyed guard makes creation idempotent so such a miss reuses the live repository rather than
     * clobbering its secret. A truly-new repository — no stored entry under this name — is the only case that
     * gets a fresh passphrase.
     */
    private BackupRepository createRepository(String name, BackupServer server) {
        return repositories.getByName(name).orElseGet(() -> {
            BackupRepository repository =
                new BackupRepository(name, server.name(), null, Passphrases.strong(), false);
            repositories.save(repository);
            return repository;
        });
    }
}
