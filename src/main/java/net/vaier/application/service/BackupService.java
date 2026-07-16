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
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
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

    public BackupService(ForPersistingBackupRepositories repositories, ForPersistingBackupServers servers,
                         ForPersistingBackupJobs jobs, ForRecordingBackupRuns runs) {
        this.repositories = repositories;
        this.servers = servers;
        this.jobs = jobs;
        this.runs = runs;
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
     */
    @Override
    public BackupJob protect(String machineName, List<String> paths) {
        BackupServer server = theBackupServer();
        String slug = BackupRepository.sanitizedName(machineName);
        Optional<BackupJob> existing = jobs.getByMachine(machineName).stream().findFirst();
        BackupRepository repository = existing
            .flatMap(job -> repositories.getByName(job.repositoryName()))
            .orElseGet(() -> createRepository(slug, server));
        List<String> newSourcePaths = existing
            .map(job -> SourcePaths.of(job.sourcePaths()).protecting(paths))
            .orElseGet(() -> SourcePaths.of(List.of()).protecting(paths))
            .paths();
        BackupJob job = existing
            .map(existingJob -> existingJob.withSourcePaths(newSourcePaths))
            .orElseGet(() -> new BackupJob(slug, machineName, repository.name(), newSourcePaths, List.of(),
                7, 4, 6, BackupJob.DEFAULT_COMPRESSION, true, false));
        jobs.save(job);
        return job;
    }

    /**
     * The inverse: remove {@code paths} (and any descendant of them) from the machine's job. A machine with
     * no job is a no-op success (empty). When the removal empties the job's protected paths the job is
     * deleted — a job must protect at least one path — leaving the repository intact.
     */
    @Override
    public Optional<BackupJob> unprotect(String machineName, List<String> paths) {
        Optional<BackupJob> existing = jobs.getByMachine(machineName).stream().findFirst();
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BackupJob job = existing.get();
        SourcePaths remaining = SourcePaths.of(job.sourcePaths()).without(paths);
        if (remaining.isEmpty()) {
            jobs.deleteByName(job.name());
            return Optional.empty();
        }
        BackupJob updated = job.withSourcePaths(remaining.paths());
        jobs.save(updated);
        return Optional.of(updated);
    }

    /** The fleet's single backup server, or a {@code 409}-mapped conflict when none is designated yet. */
    private BackupServer theBackupServer() {
        return servers.getAll().stream().findFirst()
            .orElseThrow(() -> new ConflictException(
                "Designate a backup server before backing up machines."));
    }

    /** Create and persist a machine's repository with a fresh backend-generated passphrase. */
    private BackupRepository createRepository(String name, BackupServer server) {
        BackupRepository repository =
            new BackupRepository(name, server.name(), null, Passphrases.strong(), false);
        repositories.save(repository);
        return repository;
    }
}
