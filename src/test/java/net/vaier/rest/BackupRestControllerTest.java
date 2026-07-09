package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.ListArchivesUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.service.BackupService;
import net.vaier.domain.Archive;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.Edition;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForRecordingBackupRuns;
import net.vaier.rest.BackupRestController.ArchiveResponse;
import net.vaier.rest.BackupRestController.RepositoryRequest;
import net.vaier.rest.BackupRestController.RepositoryResponse;
import net.vaier.rest.BackupRestController.RunResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.vaier.domain.BackupJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackupRestControllerTest {

    ForPersistingBackupRepositories repositories;
    ForPersistingBackupJobs jobs;
    InMemoryRuns runs;
    RunBackupJobUseCase runBackupJob;
    ListArchivesUseCase listArchives;
    CheckBackupPrerequisitesUseCase checkPrerequisites;
    InitBackupRepositoryUseCase initBackupRepository;
    BackupService service;
    BackupRestController controller;
    ObjectMapper objectMapper = new ObjectMapper();

    static final class InMemoryRepos implements ForPersistingBackupRepositories {
        final List<BackupRepository> store = new ArrayList<>();
        @Override public List<BackupRepository> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupRepository> getByName(String name) {
            return store.stream().filter(r -> r.name().equals(name)).findFirst();
        }
        @Override public void save(BackupRepository r) { store.removeIf(x -> x.name().equals(r.name())); store.add(r); }
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

    static final class InMemoryRuns implements ForRecordingBackupRuns {
        final List<BackupRun> store = new ArrayList<>();
        @Override public void record(BackupRun run) { store.add(run); }
        @Override public Optional<BackupRun> latestForJob(String jobName) {
            return store.stream().filter(r -> r.jobName().equals(jobName)).reduce((a, b) -> b);
        }
        @Override public List<BackupRun> getAll() { return List.copyOf(store); }
    }

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepos();
        jobs = new InMemoryJobs();
        runs = new InMemoryRuns();
        runBackupJob = mock(RunBackupJobUseCase.class);
        listArchives = mock(ListArchivesUseCase.class);
        checkPrerequisites = mock(CheckBackupPrerequisitesUseCase.class);
        initBackupRepository = mock(InitBackupRepositoryUseCase.class);
        service = new BackupService(repositories, jobs, runs);
        controller = new BackupRestController(service, service, service, service, service, service,
            service, runBackupJob, listArchives, checkPrerequisites, initBackupRepository);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "192.168.3.3", 8022, "borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", "Colina 27", "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true);
    }

    /** MockMvc that runs the Community-edition Enterprise gate in front of the controller. */
    private MockMvc communityMockMvc() {
        return MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new EnterpriseLicenseInterceptor(() -> Edition.COMMUNITY))
            .build();
    }

    @Test
    void communityEditionGets402OnAllRoutes() throws Exception {
        MockMvc mvc = communityMockMvc();

        mvc.perform(get("/backup-repositories")).andExpect(status().isPaymentRequired());
        mvc.perform(get("/backup-repositories/nas-borg")).andExpect(status().isPaymentRequired());
        mvc.perform(put("/backup-repositories/nas-borg")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isPaymentRequired());
        mvc.perform(delete("/backup-repositories/nas-borg")).andExpect(status().isPaymentRequired());
        mvc.perform(get("/backup-repositories/nas-borg/archives")).andExpect(status().isPaymentRequired());

        mvc.perform(get("/backup-jobs")).andExpect(status().isPaymentRequired());
        mvc.perform(get("/backup-jobs/colina-home")).andExpect(status().isPaymentRequired());
        mvc.perform(put("/backup-jobs/colina-home")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isPaymentRequired());
        mvc.perform(delete("/backup-jobs/colina-home")).andExpect(status().isPaymentRequired());

        // Slice 8: the provisioning routes are gated too.
        mvc.perform(get("/backup-jobs/colina-home/provision/check")).andExpect(status().isPaymentRequired());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/backup-repositories/nas-borg/provision/init")).andExpect(status().isPaymentRequired());
    }

    @Test
    void putThenGetRepositoryNeverReturnsPassphrase() throws Exception {
        controller.saveRepository("nas-borg",
            new RepositoryRequest("192.168.3.3", 8022, "borg", "./colina", "s3cr3t-passphrase", false));

        RepositoryResponse body = controller.getRepository("nas-borg").getBody();
        assertThat(body).isNotNull();
        assertThat(body.hasPassphrase()).isTrue();

        // The serialized DTO carries no passphrase field and never the secret value.
        String json = objectMapper.writeValueAsString(body);
        assertThat(json)
            .doesNotContain("s3cr3t-passphrase")
            .doesNotContain("passphrase");
    }

    @Test
    void editWithBlankPassphraseKeepsStoredSecret() {
        controller.saveRepository("nas-borg",
            new RepositoryRequest("192.168.3.3", 8022, "borg", "./colina", "original-secret", false));

        // Edit changing the host but leaving the passphrase blank -> keep the stored secret.
        controller.saveRepository("nas-borg",
            new RepositoryRequest("192.168.3.9", 8022, "borg", "./colina", "   ", false));

        BackupRepository stored = repositories.getByName("nas-borg").orElseThrow();
        assertThat(stored.passphrase()).isEqualTo("original-secret");
        assertThat(stored.nasHost()).isEqualTo("192.168.3.9");
    }

    @Test
    void postRunReturns202AndRunningStatus() {
        repositories.save(repo());
        jobs.save(job());
        BackupRun running = BackupRun.started(job(), "job-colina-home-1720404000000",
            Instant.parse("2026-07-08T02:00:00Z"));
        when(runBackupJob.runJob(any(), any())).thenReturn(running);

        ResponseEntity<RunResponse> response = controller.runJob("colina-home");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(response.getBody().runId()).isEqualTo("job-colina-home-1720404000000");
        assertThat(response.getBody().jobName()).isEqualTo("colina-home");
    }

    @Test
    void postRunUnknownJobReturns404() {
        ResponseEntity<RunResponse> response = controller.runJob("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(runBackupJob, never()).runJob(any(), any());
    }

    @Test
    void getRunsReturnsLatestRun() {
        runs.record(BackupRun.started(job(), "run-old", Instant.parse("2026-07-07T02:00:00Z")));
        runs.record(BackupRun.started(job(), "run-new", Instant.parse("2026-07-08T02:00:00Z")));

        ResponseEntity<RunResponse> response = controller.getRuns("colina-home");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().runId()).isEqualTo("run-new");
        assertThat(response.getBody().status()).isEqualTo(BackupRunStatus.RUNNING);
    }

    @Test
    void getRunsWhenNoneYetReturns404() {
        ResponseEntity<RunResponse> response = controller.getRuns("colina-home");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getArchivesReturnsList() {
        repositories.save(repo());
        when(listArchives.listArchives("nas-borg")).thenReturn(List.of(
            new Archive("colina-2024-06-01", "abc", Instant.parse("2024-06-01T12:00:00Z"))));

        ResponseEntity<List<ArchiveResponse>> response = controller.listArchives("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().hasSize(1);
        assertThat(response.getBody().get(0).name()).isEqualTo("colina-2024-06-01");
        assertThat(response.getBody().get(0).id()).isEqualTo("abc");
        assertThat(response.getBody().get(0).time()).isEqualTo(Instant.parse("2024-06-01T12:00:00Z"));
    }

    @Test
    void getArchivesReturnsEmptyWhenNoReferencingJob() {
        // Repo exists but nothing references it: the runner has no client host to list from and returns
        // empty; the controller reports 200 with an empty list rather than erroring.
        repositories.save(repo());
        when(listArchives.listArchives("nas-borg")).thenReturn(List.of());

        ResponseEntity<List<ArchiveResponse>> response = controller.listArchives("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    @Test
    void getArchivesUnknownRepositoryReturns404() {
        ResponseEntity<List<ArchiveResponse>> response = controller.listArchives("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(listArchives, never()).listArchives(any());
    }

    // --- Slice 8: guided provisioning ---

    @Test
    void provisionCheckReturnsStatus() {
        jobs.save(job());
        when(checkPrerequisites.checkBorg("Colina 27"))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", "Colina 27"))
            .thenReturn(new RepoReachability(true));

        ResponseEntity<BackupRestController.ProvisionCheckResponse> response =
            controller.provisionCheck("colina-home");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().borgInstalled()).isTrue();
        assertThat(response.getBody().borgVersion()).isEqualTo("1.2.8");
        assertThat(response.getBody().borgSupported()).isTrue();
        assertThat(response.getBody().nasReachable()).isTrue();
    }

    @Test
    void provisionCheckUnknownJobReturns404() {
        ResponseEntity<BackupRestController.ProvisionCheckResponse> response =
            controller.provisionCheck("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(checkPrerequisites, never()).checkBorg(any());
    }

    @Test
    void provisionCheckReportsBorgAbsentWithNullVersion() {
        jobs.save(job());
        when(checkPrerequisites.checkBorg("Colina 27"))
            .thenReturn(new BorgAvailability(false, Optional.empty(), false));
        when(checkPrerequisites.checkNas("nas-borg", "Colina 27"))
            .thenReturn(new RepoReachability(false));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck("colina-home").getBody();

        assertThat(body).isNotNull();
        assertThat(body.borgInstalled()).isFalse();
        assertThat(body.borgVersion()).isNull();
        assertThat(body.borgSupported()).isFalse();
        assertThat(body.nasReachable()).isFalse();
    }

    @Test
    void provisionInitRuns() {
        repositories.save(repo());
        jobs.save(job());
        when(initBackupRepository.initRepo("nas-borg", "Colina 27"))
            .thenReturn(new RepoInitResult(true, false, "Repository initialised"));

        ResponseEntity<BackupRestController.ProvisionInitResponse> response =
            controller.provisionInit("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().initialized()).isTrue();
        assertThat(response.getBody().alreadyExisted()).isFalse();
        verify(initBackupRepository).initRepo("nas-borg", "Colina 27");
    }

    @Test
    void provisionInitUnknownRepositoryReturns404() {
        ResponseEntity<BackupRestController.ProvisionInitResponse> response =
            controller.provisionInit("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(initBackupRepository, never()).initRepo(any(), any());
    }

    @Test
    void provisionInitNoReferencingJobReturns409() {
        // The repo exists but no job targets it, so there is no host to init from.
        repositories.save(repo());

        ResponseEntity<BackupRestController.ProvisionInitResponse> response =
            controller.provisionInit("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(initBackupRepository, never()).initRepo(any(), any());
    }
}
