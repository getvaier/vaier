package net.vaier.application.service;

import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForRecordingBackupRuns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceTest {

    InMemoryRepos repositories;
    InMemoryServers servers;
    InMemoryJobs jobs;
    InMemoryRunRecorder runs;
    BackupService service;

    static final class InMemoryServers implements ForPersistingBackupServers {
        final List<BackupServer> store = new ArrayList<>();
        @Override public List<BackupServer> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupServer> getByName(String name) {
            return store.stream().filter(s -> s.name().equals(name)).findFirst();
        }
        @Override public void save(BackupServer s) {
            store.removeIf(x -> x.name().equals(s.name())); store.add(s);
        }
        @Override public void deleteByName(String name) { store.removeIf(s -> s.name().equals(name)); }
    }

    static final class InMemoryRepos implements ForPersistingBackupRepositories {
        final List<BackupRepository> store = new ArrayList<>();
        @Override public List<BackupRepository> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupRepository> getByName(String name) {
            return store.stream().filter(r -> r.name().equals(name)).findFirst();
        }
        @Override public void save(BackupRepository r) {
            store.removeIf(x -> x.name().equals(r.name())); store.add(r);
        }
        @Override public void deleteByName(String name) { store.removeIf(r -> r.name().equals(name)); }
    }

    static final class InMemoryJobs implements ForPersistingBackupJobs {
        final List<BackupJob> store = new ArrayList<>();
        @Override public List<BackupJob> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupJob> getByName(String name) {
            return store.stream().filter(j -> j.name().equals(name)).findFirst();
        }
        @Override public List<BackupJob> getByMachine(String machineName) {
            return store.stream().filter(j -> j.machineName().equals(machineName)).toList();
        }
        @Override public void save(BackupJob j) { store.removeIf(x -> x.name().equals(j.name())); store.add(j); }
        @Override public void deleteByName(String name) { store.removeIf(j -> j.name().equals(name)); }
    }

    static final class InMemoryRunRecorder implements ForRecordingBackupRuns {
        final List<BackupRun> recorded = new ArrayList<>();
        @Override public void record(BackupRun run) { recorded.add(run); }
        @Override public Optional<BackupRun> latestForJob(String jobName) {
            return recorded.stream().filter(r -> r.jobName().equals(jobName)).reduce((a, b) -> b);
        }
        @Override public List<BackupRun> getAll() { return List.copyOf(recorded); }
    }

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepos();
        servers = new InMemoryServers();
        jobs = new InMemoryJobs();
        runs = new InMemoryRunRecorder();
        service = new BackupService(repositories, servers, jobs, runs);
    }

    private BackupServer server() {
        return new BackupServer("nas-borg", "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void crudRepositoriesAndJobs() {
        service.saveBackupRepository(repo());
        assertThat(service.getBackupRepositories()).containsExactly(repo());

        service.saveBackupJob(job());
        assertThat(service.getBackupJobs()).containsExactly(job());

        service.deleteBackupJob("colina-home");
        assertThat(service.getBackupJobs()).isEmpty();

        service.deleteBackupRepository("nas-borg");
        assertThat(service.getBackupRepositories()).isEmpty();
    }

    @Test
    void crudBackupServers() {
        assertThat(service.getBackupServers()).isEmpty();

        service.saveBackupServer(server());
        assertThat(service.getBackupServers()).containsExactly(server());

        service.deleteBackupServer("nas-borg");
        assertThat(service.getBackupServers()).isEmpty();
    }

    @Test
    void savingABackupServerWithTheSameNameReplacesIt() {
        service.saveBackupServer(server());
        BackupServer moved = new BackupServer("nas-borg", "NAS", "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        service.saveBackupServer(moved);

        assertThat(service.getBackupServers()).containsExactly(moved);
    }

    @Test
    void latestForJobIsServedFromTheRunRecorderPort() {
        // GetBackupRunsUseCase reads purely from the driven run-recorder port — no SSH, no rest layer.
        assertThat(service.latestForJob("colina-home")).isEmpty();

        BackupRun older = BackupRun.started(job(), "run-1", Instant.parse("2026-07-07T02:00:00Z"));
        BackupRun newer = BackupRun.started(job(), "run-2", Instant.parse("2026-07-08T02:00:00Z"));
        runs.record(older);
        runs.record(newer);

        assertThat(service.latestForJob("colina-home")).contains(newer);
    }

    @Test
    void theFleetHasAtMostOneBackupServer() {
        // (c) first server on an empty store is allowed.
        service.saveBackupServer(server());
        assertThat(service.getBackupServers()).containsExactly(server());

        // (b) re-saving the SAME-named server (an edit) still replaces in place.
        BackupServer moved = new BackupServer("nas-borg", "NAS", "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        service.saveBackupServer(moved);
        assertThat(service.getBackupServers()).containsExactly(moved);

        // (a) a second, differently-named server is rejected and not stored.
        BackupServer another = new BackupServer("other-borg", "Other", "192.168.3.4", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        assertThatThrownBy(() -> service.saveBackupServer(another))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nas-borg");

        assertThat(service.getBackupServers()).containsExactly(moved);
    }

    @Test
    void savingAJobForAnUnknownRepositoryIsRejected() {
        // No repository saved yet -> the job references a repository that does not exist.
        assertThatThrownBy(() -> service.saveBackupJob(job()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nas-borg");

        assertThat(service.getBackupJobs()).isEmpty();
    }

    // --- Just select and back up: get-or-create semantics ---

    @Test
    void protectGetsOrCreatesRepositoryAndJobWithABackendPassphrase() {
        service.saveBackupServer(server());

        BackupJob created = service.protect("Colina 27", List.of("/home/geir"));

        // The repository is created by the machine's sanitized name, on the single server, with a strong
        // backend-generated passphrase (never taken from a client).
        BackupRepository repo = repositories.getByName("Colina-27").orElseThrow();
        assertThat(repo.serverName()).isEqualTo("nas-borg");
        assertThat(repo.passphrase()).matches("[A-Za-z0-9]{32}");
        // The job is created for the machine, referencing that repository, with the retention defaults.
        assertThat(created.machineName()).isEqualTo("Colina 27");
        assertThat(created.repositoryName()).isEqualTo("Colina-27");
        assertThat(created.sourcePaths()).containsExactly("/home/geir");
        assertThat(created.keepDaily()).isEqualTo(7);
        assertThat(created.enabled()).isTrue();
    }

    @Test
    void protectReusesTheExistingRepositoryAndJobForTheMachine() {
        service.saveBackupServer(server());
        service.protect("Colina 27", List.of("/home/geir"));

        service.protect("Colina 27", List.of("/etc/nginx"));

        // Still exactly one repository and one job — the second call folds into the same job.
        assertThat(repositories.getAll()).hasSize(1);
        assertThat(jobs.getAll()).hasSize(1);
        assertThat(jobs.getAll().get(0).sourcePaths())
            .containsExactlyInAnyOrder("/home/geir", "/etc/nginx");
    }

    @Test
    void protectWithoutABackupServerConflicts() {
        assertThatThrownBy(() -> service.protect("Colina 27", List.of("/home/geir")))
            .isInstanceOf(net.vaier.domain.ConflictException.class)
            .hasMessageContaining("Designate a backup server");
        assertThat(jobs.getAll()).isEmpty();
        assertThat(repositories.getAll()).isEmpty();
    }

    @Test
    void unprotectEmptyingTheJobDeletesItButKeepsTheRepository() {
        service.saveBackupServer(server());
        service.protect("Colina 27", List.of("/home/geir"));

        Optional<BackupJob> result = service.unprotect("Colina 27", List.of("/home/geir"));

        assertThat(result).isEmpty();
        assertThat(jobs.getAll()).isEmpty();
        assertThat(repositories.getByName("Colina-27")).isPresent();
    }

    @Test
    void unprotectForAMachineWithNoJobIsANoOp() {
        assertThat(service.unprotect("Colina 27", List.of("/home/geir"))).isEmpty();
    }
}
