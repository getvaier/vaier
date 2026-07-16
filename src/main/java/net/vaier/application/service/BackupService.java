package net.vaier.application.service;

import net.vaier.application.DeleteBackupJobUseCase;
import net.vaier.application.DeleteBackupRepositoryUseCase;
import net.vaier.application.DeleteBackupServerUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.SaveBackupJobUseCase;
import net.vaier.application.SaveBackupRepositoryUseCase;
import net.vaier.application.SaveBackupServerUseCase;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
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
    SaveBackupJobUseCase, GetBackupJobsUseCase, DeleteBackupJobUseCase, GetBackupRunsUseCase {

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
}
