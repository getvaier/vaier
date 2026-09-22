package net.vaier.rest;

import net.vaier.domain.NotFoundException;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.MachineId;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.AuthorizeBackupClientUseCase;
import net.vaier.application.AuthorizeBackupClientUseCase.AuthorizeResult;
import net.vaier.application.CheckBackupPrerequisitesUseCase;
import net.vaier.application.CheckBackupPrerequisitesUseCase.BorgAvailability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.RepoReachability;
import net.vaier.application.CheckBackupPrerequisitesUseCase.ServerBorgAuth;
import net.vaier.application.GenerateBackupServerSetupScriptUseCase;
import net.vaier.application.InitBackupRepositoryUseCase;
import net.vaier.application.InitBackupRepositoryUseCase.RepoInitResult;
import net.vaier.application.ListArchivesUseCase;
import net.vaier.application.PrepareBackupClientUseCase;
import net.vaier.application.PrepareBackupClientUseCase.PrepareResult;
import net.vaier.application.PrepareBackupClientUseCase.PrepareState;
import net.vaier.application.PrepareBackupClientUseCase.PrepareStatus;
import net.vaier.application.ProvisionBackupServerUseCase;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionResult;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionState;
import net.vaier.application.ProvisionBackupServerUseCase.ProvisionStatus;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.RunBackupJobUseCase;
import net.vaier.application.WriteSurvivalKitUseCase;
import net.vaier.application.WriteSurvivalKitUseCase.SurvivalKitReport;
import net.vaier.application.service.BackupService;
import net.vaier.domain.Archive;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.BorgVersion;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.ConflictException;
import net.vaier.domain.SurvivalKitHosts;
import net.vaier.domain.SurvivalKitRollout;
import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForReadyingBackupClients;
import net.vaier.domain.port.ForReadyingBackupClients.ReadyingOutcome;
import net.vaier.domain.port.ForRecordingBackupRuns;
import net.vaier.rest.BackupRestController.ArchiveResponse;
import net.vaier.rest.BackupRestController.RepositoryRequest;
import net.vaier.rest.BackupRestController.RepositoryResponse;
import net.vaier.rest.BackupRestController.RunResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.vaier.domain.BackupJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.times;

class BackupRestControllerTest {

    ForPersistingBackupRepositories repositories;
    InMemoryServers backupServers;
    ForPersistingBackupJobs jobs;
    InMemoryRuns runs;
    RunBackupJobUseCase runBackupJob;
    ListArchivesUseCase listArchives;
    CheckBackupPrerequisitesUseCase checkPrerequisites;
    InitBackupRepositoryUseCase initBackupRepository;
    ProvisionBackupServerUseCase provisionBackupServer;
    GenerateBackupServerSetupScriptUseCase generateSetupScript;
    GetMachinesUseCase getMachines;
    AuthorizeBackupClientUseCase authorizeBackupClient;
    PrepareBackupClientUseCase prepareBackupClient;
    ForReadyingBackupClients readier;
    net.vaier.domain.port.ForSubscribingToEvents forSubscribingToEvents;
    WriteSurvivalKitUseCase writeSurvivalKit;
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
        @Override public List<BackupJob> getByMachine(MachineId machineId) {
            return store.stream().filter(j -> j.machineId().equals(machineId)).toList();
        }
        @Override public void save(BackupJob j) { store.removeIf(x -> x.machineId().equals(j.machineId())); store.add(j); }
        @Override public void deleteByMachine(MachineId machineId) { store.removeIf(j -> j.machineId().equals(machineId)); }
    }

    static final class InMemoryServers implements ForPersistingBackupServers {
        final List<BackupServer> store = new ArrayList<>();
        @Override public List<BackupServer> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupServer> getByName(String name) {
            return store.stream().filter(s -> s.name().equals(name)).findFirst();
        }
        @Override public void save(BackupServer s) { store.removeIf(x -> x.name().equals(s.name())); store.add(s); }
        @Override public void deleteByName(String name) { store.removeIf(s -> s.name().equals(name)); }
    }

    static final class InMemoryRuns implements ForRecordingBackupRuns {
        final List<BackupRun> store = new ArrayList<>();
        @Override public void record(BackupRun run) { store.add(run); }
        @Override public Optional<BackupRun> latestForMachine(MachineId machineId) {
            return store.stream().filter(r -> r.machineId().equals(machineId)).reduce((a, b) -> b);
        }
        @Override public List<BackupRun> getAll() { return List.copyOf(store); }
    }

    /** The machine the fixture job ("colina-home") backs up — the address of every job endpoint now. */
    private static final MachineId COLINA = TestMachineIds.of("Colina 27");

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepos();
        backupServers = new InMemoryServers();
        jobs = new InMemoryJobs();
        runs = new InMemoryRuns();
        runBackupJob = mock(RunBackupJobUseCase.class);
        listArchives = mock(ListArchivesUseCase.class);
        checkPrerequisites = mock(CheckBackupPrerequisitesUseCase.class);
        initBackupRepository = mock(InitBackupRepositoryUseCase.class);
        provisionBackupServer = mock(ProvisionBackupServerUseCase.class);
        writeSurvivalKit = mock(WriteSurvivalKitUseCase.class);
        generateSetupScript = mock(GenerateBackupServerSetupScriptUseCase.class);
        getMachines = mock(GetMachinesUseCase.class);
        authorizeBackupClient = mock(AuthorizeBackupClientUseCase.class);
        prepareBackupClient = mock(PrepareBackupClientUseCase.class);
        readier = mock(ForReadyingBackupClients.class);
        forSubscribingToEvents = mock(net.vaier.domain.port.ForSubscribingToEvents.class);
        service = new BackupService(repositories, backupServers, jobs, runs, readier);
        controller = new BackupRestController(service, service, service, service, service, service,
            generateSetupScript, provisionBackupServer, service, service, service, service, runBackupJob,
            listArchives, checkPrerequisites, initBackupRepository, getMachines, authorizeBackupClient,
            prepareBackupClient, service, forSubscribingToEvents, writeSurvivalKit, service);
    }

    private Machine machine(String name) {
        return new Machine(TestMachineIds.of(name), name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private BackupServer server() {
        return new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void theJobList_carriesEachJobsLastOutcome() throws Exception {
        // The Explorer's tree colours a machine's backup entry from this, so trouble is visible without
        // opening anything. Reading it per job on view was the old shape, and it meant an operator had to
        // walk into every machine to find the one that failed — the opposite of what a tree is for.
        jobs.save(job());
        runs.record(BackupRun.failed(job(), "run-1", Instant.parse("2026-07-22T02:00:00Z"), "borg died"));

        String body = mockMvc().perform(get("/backup-jobs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"lastRunStatus\":\"FAILED\"");
    }

    @Test
    void aJobThatHasNeverRun_saysSoRatherThanClaimingAnOutcome() throws Exception {
        // A missing status is its own fact — "no run yet" is not success, and a dot that guesses either way
        // would be the tree's first lie.
        jobs.save(job());

        String body = mockMvc().perform(get("/backup-jobs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"lastRunStatus\":null");
    }

    @Test
    void theLists_resolveTheFleetOnce_notOncePerRow() throws Exception {
        // Five jobs meant five reads of the whole fleet for one list — each a walk of the tunnel — and
        // the repository list read it once more per row on top. The boot's slowest requests were these.
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27"), machine("NAS")));
        jobs.save(job());
        jobs.save(new BackupJob("nas-home", TestMachineIds.of("NAS"), "nas-borg",
            List.of("/volume1"), List.of(), 7, 4, 6, "zstd,6", true, false));
        repositories.save(repo());
        repositories.save(new BackupRepository("nas-other", "nas-borg", "./other", "s3cr3t", false));

        mockMvc().perform(get("/backup-jobs")).andExpect(status().isOk());
        verify(getMachines, times(1)).getAllMachines();

        mockMvc().perform(get("/backup-repositories")).andExpect(status().isOk());
        verify(getMachines, times(2)).getAllMachines();
    }

    @Test
    void putThenGetRepositoryNeverReturnsPassphrase() throws Exception {
        controller.saveRepository("nas-borg",
            new RepositoryRequest("nas-borg", "./colina", "s3cr3t-passphrase", false));

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
    void getRepositoryReturnsEffectiveRepoPathDerivedFromServer() {
        // A repository added by name only (no path override) derives its path from its server. The response
        // shows the effective path so the UI can display where the store points.
        backupServers.save(server());
        controller.saveRepository("colina27", new RepositoryRequest("nas-borg", null, "s3cr3t", false));

        RepositoryResponse body = controller.getRepository("colina27").getBody();
        assertThat(body).isNotNull();
        assertThat(body.serverName()).isEqualTo("nas-borg");
        assertThat(body.repoPath()).isEqualTo("home/borg/backups/colina27");
    }

    @Test
    void editWithBlankPassphraseKeepsStoredSecret() {
        controller.saveRepository("nas-borg",
            new RepositoryRequest("nas-borg", "./colina", "original-secret", false));

        // Edit changing the server but leaving the passphrase blank -> keep the stored secret.
        controller.saveRepository("nas-borg",
            new RepositoryRequest("other-server", "./colina", "   ", false));

        BackupRepository stored = repositories.getByName("nas-borg").orElseThrow();
        assertThat(stored.passphrase()).isEqualTo("original-secret");
        assertThat(stored.serverName()).isEqualTo("other-server");
    }

    @Test
    void postRunReturns202AndRunningStatus() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        BackupRun running = BackupRun.started(job(), "job-colina-home-1720404000000",
            Instant.parse("2026-07-08T02:00:00Z"));
        when(runBackupJob.runJob(any(), any())).thenReturn(running);

        ResponseEntity<RunResponse> response = controller.runJob(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BackupRunStatus.RUNNING);
        assertThat(response.getBody().runId()).isEqualTo("job-colina-home-1720404000000");
        assertThat(response.getBody().jobName()).isEqualTo("colina-home");
    }

    @Test
    void postRunUnknownJobReturns404() {
        ResponseEntity<RunResponse> response = controller.runJob(MachineId.generate().value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(runBackupJob, never()).runJob(any(), any());
    }

    @Test
    void postRunUnknownBackupServerReturns404() {
        // The job and its repository exist, but the repository's Backup server is not configured.
        repositories.save(repo());
        jobs.save(job());

        ResponseEntity<RunResponse> response = controller.runJob(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(runBackupJob, never()).runJob(any(), any());
    }

    @Test
    void getRunsReturnsLatestRun() {
        runs.record(BackupRun.started(job(), "run-old", Instant.parse("2026-07-07T02:00:00Z")));
        runs.record(BackupRun.started(job(), "run-new", Instant.parse("2026-07-08T02:00:00Z")));

        ResponseEntity<RunResponse> response = controller.getRuns(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().runId()).isEqualTo("run-new");
        assertThat(response.getBody().status()).isEqualTo(BackupRunStatus.RUNNING);
    }

    @Test
    void getRunsWhenNoneYetReturns404() {
        ResponseEntity<RunResponse> response = controller.getRuns(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getRunsExposesTheRunDiagnosticsWithoutBorgsJsonStats() {
        // A real INCOMPLETE run: borg's denied-file lines, then its --json --stats object. It used to be
        // recorded WARNING — a non-failure — which is precisely how a machine went months with holes in its
        // archives while the run list read as fine.
        String summary = """
            /home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
            {
                "archive": {"name": "ip-172-31-17-253-2026-07-13T00:07:47"}
            }""";
        runs.record(BackupRun.fromExitCode(job(), "run-warn", Instant.parse("2026-07-13T00:07:41Z"),
            Instant.parse("2026-07-13T00:08:02Z"), 1, summary));

        ResponseEntity<RunResponse> response = controller.getRuns(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(BackupRunStatus.INCOMPLETE);
        // The raw summary is still carried verbatim...
        assertThat(response.getBody().summary()).isEqualTo(summary);
        // ...and the diagnostics are the part worth showing a human — no JSON stats blob.
        assertThat(response.getBody().diagnostics()).isEqualTo(
            "/home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'");
    }

    @Test
    void getRunsReportsNoDiagnosticsForACleanRun() {
        runs.record(BackupRun.fromExitCode(job(), "run-ok", Instant.parse("2026-07-13T02:00:00Z"),
            Instant.parse("2026-07-13T02:05:00Z"), 0, "{\n    \"archive\": {}\n}"));

        ResponseEntity<RunResponse> response = controller.getRuns(COLINA.value());

        assertThat(response.getBody()).isNotNull();
        // The happy path has nothing to disclose — the UI shows no affordance for this.
        assertThat(response.getBody().diagnostics()).isEmpty();
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
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27")))
            .thenReturn(new RepoReachability(true));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.of(new BorgVersion(1, 2, 8))))
            .thenReturn(new ServerBorgAuth(true, Optional.of(new BorgVersion(1, 4, 3)), true));

        ResponseEntity<BackupRestController.ProvisionCheckResponse> response =
            controller.provisionCheck(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().borgInstalled()).isTrue();
        assertThat(response.getBody().borgVersion()).isEqualTo("1.2.8");
        assertThat(response.getBody().borgSupported()).isTrue();
        assertThat(response.getBody().nasReachable()).isTrue();
        // Slice 5: the auth+version fields the wizard needs to avoid a false all-green.
        assertThat(response.getBody().borgAuthOk()).isTrue();
        assertThat(response.getBody().serverBorgVersion()).isEqualTo("1.4.3");
        assertThat(response.getBody().versionsCompatible()).isTrue();
    }

    @Test
    void provisionCheckSurfacesTheFalseAllGreenWhenTheClientKeyIsNotTrusted() {
        // The exact live-hardware regression: borg installed, NAS port open, but the client cannot
        // authenticate. The response MUST carry borgAuthOk=false so it cannot read as ready.
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27")))
            .thenReturn(new RepoReachability(true));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.of(new BorgVersion(1, 2, 8))))
            .thenReturn(new ServerBorgAuth(false, Optional.empty(), false));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck(COLINA.value()).getBody();

        assertThat(body).isNotNull();
        // borg and the port both look fine...
        assertThat(body.borgInstalled()).isTrue();
        assertThat(body.nasReachable()).isTrue();
        // ...but auth is the truth: not ready.
        assertThat(body.borgAuthOk()).isFalse();
        assertThat(body.serverBorgVersion()).isNull();
        assertThat(body.versionsCompatible()).isFalse();
    }

    // --- Back up as root: the DTOs carry it, and the readiness check only judges a job that asked for it ---

    /** The same job with "Back up as root" on. */
    private BackupJob rootJob() {
        return new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, true);
    }

    @Test
    void saveJobPersistsBackupAsRootAndReturnsItOnTheJob() {
        repositories.save(repo());
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.JobResponse> response = controller.saveJob(COLINA.value(),
            new BackupRestController.JobRequest("nas-borg", List.of("/home/geir"),
                List.of(), 7, 4, 6, "zstd,6", true, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().backupAsRoot()).isTrue();
        assertThat(jobs.getByMachine(COLINA)).singleElement()
            .extracting(BackupJob::backupAsRoot).isEqualTo(true);
    }

    /**
     * The dead-job bug, closed at the door. Two jobs — {@code NUC 02} and {@code NUC02} — ran nightly for
     * months against machines in no registry, because a job stored whatever machine name it was handed. A job
     * now holds a machine's identity, and an identity can only come from a machine that exists.
     */
    @Test
    void saveJobForAMachineTheFleetDoesNotHaveIsRefused() {
        repositories.save(repo());
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        MachineId nuc02 = TestMachineIds.of("NUC02");

        assertThatThrownBy(() -> controller.saveJob(nuc02.value(),
            new BackupRestController.JobRequest("nas-borg", List.of("/home/geir"),
                List.of(), 7, 4, 6, "zstd,6", true, false)))
            .isInstanceOf(NotFoundException.class)
            // The identity that was addressed, since that is the whole address — there is no name in it to
            // echo back, which is the point: a job can no longer be stored against a string.
            .hasMessageContaining(nuc02.value());
        assertThat(jobs.getByMachine(nuc02)).isEmpty();
    }

    /** Opting out is just as explicit — and is the default a job is created with. */
    @Test
    void saveJobWithoutBackupAsRootKeepsTheJobRunningAsTheSshUser() {
        repositories.save(repo());
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.JobResponse> response = controller.saveJob(COLINA.value(),
            new BackupRestController.JobRequest("nas-borg", List.of("/home/geir"),
                List.of(), 7, 4, 6, "zstd,6", true, false));

        assertThat(response.getBody().backupAsRoot()).isFalse();
        assertThat(jobs.getByMachine(COLINA)).singleElement()
            .extracting(BackupJob::backupAsRoot).isEqualTo(false);
    }

    /**
     * For a job that HAS opted in, the wizard reports whether the machine can actually run borg as root —
     * otherwise the run silently skips exactly the files the operator turned this on to capture.
     */
    @Test
    void provisionCheckReportsRootBorgForAJobThatBacksUpAsRoot() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(rootJob());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27"))).thenReturn(new RepoReachability(true));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.of(new BorgVersion(1, 2, 8))))
            .thenReturn(new ServerBorgAuth(true, Optional.of(new BorgVersion(1, 4, 3)), true));
        when(checkPrerequisites.checkRootBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new CheckBackupPrerequisitesUseCase.RootBorgAvailability(true));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck(COLINA.value()).getBody();

        assertThat(body.backupAsRoot()).isTrue();
        assertThat(body.rootBorgOk()).isTrue();
    }

    /** An opted-in job on a host with no sudo grant is honestly reported as NOT ready. */
    @Test
    void provisionCheckReportsRootBorgMissingForAJobThatBacksUpAsRoot() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(rootJob());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27"))).thenReturn(new RepoReachability(true));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.of(new BorgVersion(1, 2, 8))))
            .thenReturn(new ServerBorgAuth(true, Optional.of(new BorgVersion(1, 4, 3)), true));
        when(checkPrerequisites.checkRootBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new CheckBackupPrerequisitesUseCase.RootBorgAvailability(false));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck(COLINA.value()).getBody();

        assertThat(body.backupAsRoot()).isTrue();
        assertThat(body.rootBorgOk()).isFalse();
    }

    /**
     * A job with the toggle OFF must not be shown as failing this check — it does not need root, and is not
     * "not ready" for lacking a grant it will never use. The check is not even run for it.
     */
    @Test
    void provisionCheckDoesNotJudgeRootBorgForAJobThatDoesNotBackUpAsRoot() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(true, Optional.of(new BorgVersion(1, 2, 8)), true));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27"))).thenReturn(new RepoReachability(true));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.of(new BorgVersion(1, 2, 8))))
            .thenReturn(new ServerBorgAuth(true, Optional.of(new BorgVersion(1, 4, 3)), true));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck(COLINA.value()).getBody();

        assertThat(body.backupAsRoot()).isFalse();
        // Not probed at all — no pointless SSH round trip for a check that cannot apply.
        verify(checkPrerequisites, never()).checkRootBorg(any());
    }

    @Test
    void provisionCheckUnknownJobReturns404() {
        ResponseEntity<BackupRestController.ProvisionCheckResponse> response =
            controller.provisionCheck(MachineId.generate().value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(checkPrerequisites, never()).checkBorg(any());
    }

    @Test
    void provisionCheckUnknownBackupServerReturns404() {
        // The job and its repository exist, but the repository's Backup server is not configured.
        repositories.save(repo());
        jobs.save(job());

        ResponseEntity<BackupRestController.ProvisionCheckResponse> response =
            controller.provisionCheck(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(checkPrerequisites, never()).checkBorg(any());
    }

    @Test
    void provisionCheckReportsBorgAbsentWithNullVersion() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(checkPrerequisites.checkBorg(TestMachineIds.of("Colina 27")))
            .thenReturn(new BorgAvailability(false, Optional.empty(), false));
        when(checkPrerequisites.checkNas("nas-borg", TestMachineIds.of("Colina 27")))
            .thenReturn(new RepoReachability(false));
        when(checkPrerequisites.checkServerAuth("nas-borg", TestMachineIds.of("Colina 27"), Optional.empty()))
            .thenReturn(new ServerBorgAuth(false, Optional.empty(), false));

        BackupRestController.ProvisionCheckResponse body =
            controller.provisionCheck(COLINA.value()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.borgInstalled()).isFalse();
        assertThat(body.borgVersion()).isNull();
        assertThat(body.borgSupported()).isFalse();
        assertThat(body.nasReachable()).isFalse();
        assertThat(body.borgAuthOk()).isFalse();
        assertThat(body.serverBorgVersion()).isNull();
        assertThat(body.versionsCompatible()).isFalse();
    }

    @Test
    void provisionInitRuns() {
        repositories.save(repo());
        jobs.save(job());
        when(initBackupRepository.initRepo("nas-borg", TestMachineIds.of("Colina 27")))
            .thenReturn(new RepoInitResult(true, false, "Repository initialised"));

        ResponseEntity<BackupRestController.ProvisionInitResponse> response =
            controller.provisionInit("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().initialized()).isTrue();
        assertThat(response.getBody().alreadyExisted()).isFalse();
        verify(initBackupRepository).initRepo("nas-borg", TestMachineIds.of("Colina 27"));
    }

    @Test
    void provisionInitUnknownRepositoryReturns404() {
        ResponseEntity<BackupRestController.ProvisionInitResponse> response =
            controller.provisionInit(MachineId.generate().value());

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

    // --- Prepare client (install borg) ---

    @Test
    void prepareClientResolvesTheJobsMachineAndReturnsTheResult() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(prepareBackupClient.prepareClient(TestMachineIds.of("Colina 27")))
            .thenReturn(new PrepareResult(false, false, true, "Preparing client on Colina 27", null));

        ResponseEntity<BackupRestController.PrepareClientResponse> response =
            controller.prepareClient(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().started()).isTrue();
        assertThat(response.getBody().scriptOnly()).isFalse();
        assertThat(response.getBody().stagedScriptPath()).isNull();
        verify(prepareBackupClient).prepareClient(TestMachineIds.of("Colina 27"));
    }

    @Test
    void prepareClientScriptOnlyCarriesTheStagedPath() {
        jobs.save(job());
        when(prepareBackupClient.prepareClient(TestMachineIds.of("Colina 27")))
            .thenReturn(new PrepareResult(false, true, false,
                "Vaier cannot gain root over SSH on Colina 27. The prepare-client script has been placed at "
                    + "/home/geir/.vaier-backup/prepare-client-Colina-27.sh — run: sudo bash "
                    + "/home/geir/.vaier-backup/prepare-client-Colina-27.sh",
                "/home/geir/.vaier-backup/prepare-client-Colina-27.sh"));

        BackupRestController.PrepareClientResponse body =
            controller.prepareClient(COLINA.value()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.scriptOnly()).isTrue();
        assertThat(body.stagedScriptPath()).isEqualTo("/home/geir/.vaier-backup/prepare-client-Colina-27.sh");
    }

    @Test
    void prepareClientUnknownJobReturns404() {
        ResponseEntity<BackupRestController.PrepareClientResponse> response =
            controller.prepareClient(MachineId.generate().value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(prepareBackupClient, never()).prepareClient(any());
    }

    // --- accepting "back up as root": one call, one honest compound answer (#334) ---

    @Test
    void backUpAsRoot_flipsTheJobWhenTheMachineAlreadyGrantsRootBorg() {
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.canBackUpAsRoot(COLINA)).thenReturn(true);

        BackupRestController.BackUpAsRootResponse body = controller.backUpAsRoot(COLINA.value()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.granted()).isTrue();
        assertThat(body.job().backupAsRoot()).isTrue();
        assertThat(body.provisioning()).as("nothing needed installing, so nothing is reported").isNull();
        assertThat(jobs.getByMachine(COLINA).get(0).backupAsRoot()).isTrue();
    }

    @Test
    void backUpAsRoot_reportsAnUninstalledGrantRatherThanClaimingTheToggleLanded() {
        // The acceptance criterion the old two-step flow could not meet: the operator is TOLD when the grant
        // did not go in, instead of being left with a ticked box and a job that dies on `sudo -n` tonight.
        repositories.save(repo());
        backupServers.save(server());
        jobs.save(job());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.canBackUpAsRoot(COLINA)).thenReturn(false);
        when(readier.readyForBackup(COLINA)).thenReturn(new ReadyingOutcome(false, true,
            "/home/geir/.vaier-backup/prepare-client.sh", "Run it yourself with sudo"));

        BackupRestController.BackUpAsRootResponse body = controller.backUpAsRoot(COLINA.value()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.granted()).isFalse();
        assertThat(body.job().backupAsRoot()).isFalse();
        assertThat(body.provisioning()).isNotNull();
        assertThat(body.provisioning().scriptOnly()).isTrue();
        assertThat(body.provisioning().stagedScriptPath()).isEqualTo(
            "/home/geir/.vaier-backup/prepare-client.sh");
        assertThat(jobs.getByMachine(COLINA).get(0).backupAsRoot()).isFalse();
    }

    @Test
    void backUpAsRoot_onAMachineWithNothingBackedUpIs404() {
        assertThatThrownBy(() -> controller.backUpAsRoot(MachineId.generate().value()))
            .isInstanceOf(NotFoundException.class);
        verify(readier, never()).canBackUpAsRoot(any());
    }

    @Test
    void prepareClientStatusReturnsStateAndLogTail() {
        jobs.save(job());
        when(prepareBackupClient.prepareClientStatus(TestMachineIds.of("Colina 27")))
            .thenReturn(new PrepareStatus(PrepareState.SUCCESS, "==> Vaier Backup client setup complete."));

        ResponseEntity<BackupRestController.PrepareClientStatusResponse> response =
            controller.prepareClientStatus(COLINA.value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("SUCCESS");
        assertThat(response.getBody().logTail()).contains("setup complete");
        verify(prepareBackupClient).prepareClientStatus(TestMachineIds.of("Colina 27"));
    }

    @Test
    void prepareClientStatusUnknownJobReturns404() {
        ResponseEntity<BackupRestController.PrepareClientStatusResponse> response =
            controller.prepareClientStatus(MachineId.generate().value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(prepareBackupClient, never()).prepareClientStatus(any());
    }

    @Test
    void backupEventsSubscribesToTheBackupsSseTopic() {
        // The frontend never polls — it opens this SSE stream. The controller just hands back a subscription
        // to the "backups" topic the backend publishes prepare-client settle events on.
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(forSubscribingToEvents.subscribe("backups")).thenReturn(emitter);

        assertThat(controller.backupEvents()).isSameAs(emitter);
        verify(forSubscribingToEvents).subscribe("backups");
    }

    // --- Slice 3: backup-server CRUD, setup.sh, provision ---

    @Test
    void putThenGetServerRoundTrips() {
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("NAS")));

        controller.saveServer("nas-borg", new BackupRestController.ServerRequest(
            TestMachineIds.of("NAS").value(), "192.168.3.3", 8022, "borg", "home/borg/backups", "/volume1/docker/borg", true));

        BackupRestController.ServerResponse body = controller.getServer("nas-borg").getBody();
        assertThat(body).isNotNull();
        assertThat(body.name()).isEqualTo("nas-borg");
        assertThat(body.machineName()).isEqualTo("NAS");
        assertThat(body.host()).isEqualTo("192.168.3.3");
        assertThat(body.sshPort()).isEqualTo(8022);
        assertThat(body.serverDataPath()).isEqualTo("/volume1/docker/borg");
        assertThat(body.managed()).isTrue();
    }

    @Test
    void saveServerDefaultsSshPortWhenOmitted() {
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("NAS")));

        controller.saveServer("nas-borg", new BackupRestController.ServerRequest(
            TestMachineIds.of("NAS").value(), "192.168.3.3", null, null, null, "/volume1/docker/borg", true));

        BackupServer stored = backupServers.getByName("nas-borg").orElseThrow();
        assertThat(stored.sshPort()).isEqualTo(BackupServer.DEFAULT_SSH_PORT);
        assertThat(stored.borgUser()).isEqualTo(BackupServer.DEFAULT_BORG_USER);
    }

    @Test
    void getServerUnknownReturns404() {
        assertThat(controller.getServer("nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteServerRemovesIt() {
        backupServers.save(server());

        controller.deleteServer("nas-borg");

        assertThat(backupServers.getByName("nas-borg")).isEmpty();
    }

    @Test
    void setupScriptReturnsAttachmentWithShContentType() throws Exception {
        backupServers.save(server());
        when(generateSetupScript.generateSetupScript("nas-borg"))
            .thenReturn(Optional.of("#!/usr/bin/env bash\nimage: horaceworblehat/borg-server:2.8.6\n"));
        MockMvc mvc = mockMvc();

        mvc.perform(get("/backup-servers/nas-borg/setup.sh"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Content-Disposition", "attachment; filename=nas-borg-setup.sh"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().contentType("application/x-sh"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .content().string(org.hamcrest.Matchers.containsString(
                    "image: horaceworblehat/borg-server:2.8.6")));
    }

    @Test
    void setupScriptUnknownServerReturns404() throws Exception {
        when(generateSetupScript.generateSetupScript("nope")).thenReturn(Optional.empty());
        MockMvc mvc = mockMvc();

        mvc.perform(get("/backup-servers/nope/setup.sh")).andExpect(status().isNotFound());
    }

    @Test
    void provisionServerLaunchesAndReturnsStartedResult() {
        backupServers.save(server());
        when(provisionBackupServer.provision("nas-borg"))
            .thenReturn(new ProvisionResult(false, false, true, "Provisioning started on NAS", null));

        ResponseEntity<BackupRestController.ProvisionServerResponse> response =
            controller.provisionServer("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().provisioned()).isFalse();
        assertThat(response.getBody().scriptOnly()).isFalse();
        assertThat(response.getBody().started()).isTrue();
        assertThat(response.getBody().stagedScriptPath()).isNull();
        verify(provisionBackupServer).provision("nas-borg");
    }

    @Test
    void provisionServerScriptOnlyCarriesTheStagedScriptPath() {
        // When Vaier stages the setup script over SSH (the Synology case), the response carries the exact
        // on-host path so the UI can render `sudo bash <path>` precisely rather than parsing the message prose.
        backupServers.save(server());
        when(provisionBackupServer.provision("nas-borg"))
            .thenReturn(new ProvisionResult(false, true, false,
                "Vaier cannot drive docker over SSH on NAS. The setup script has been placed at "
                    + "/home/geir/.vaier-backup/nas-borg-borg-setup.sh — run: sudo bash "
                    + "/home/geir/.vaier-backup/nas-borg-borg-setup.sh",
                "/home/geir/.vaier-backup/nas-borg-borg-setup.sh"));

        ResponseEntity<BackupRestController.ProvisionServerResponse> response =
            controller.provisionServer("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scriptOnly()).isTrue();
        assertThat(response.getBody().stagedScriptPath())
            .isEqualTo("/home/geir/.vaier-backup/nas-borg-borg-setup.sh");
    }

    @Test
    void provisionServerUnknownReturns404() {
        ResponseEntity<BackupRestController.ProvisionServerResponse> response =
            controller.provisionServer("nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(provisionBackupServer, never()).provision(any());
    }

    @Test
    void provisionStatusReturnsStateAndLogTail() {
        backupServers.save(server());
        when(provisionBackupServer.provisionStatus("nas-borg"))
            .thenReturn(new ProvisionStatus(ProvisionState.SUCCESS, "==> setup complete"));

        ResponseEntity<BackupRestController.ProvisionStatusResponse> response =
            controller.provisionStatus("nas-borg");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().state()).isEqualTo("SUCCESS");
        assertThat(response.getBody().logTail()).isEqualTo("==> setup complete");
        verify(provisionBackupServer).provisionStatus("nas-borg");
    }

    @Test
    void provisionStatusUnknownServerReturns404() {
        ResponseEntity<BackupRestController.ProvisionStatusResponse> response =
            controller.provisionStatus("nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(provisionBackupServer, never()).provisionStatus(any());
    }

    // --- Slice 4: SSH key trust (closes #320) ---

    @Test
    void authorizeReturns200WithResult() {
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(authorizeBackupClient.authorizeClient("nas-borg", TestMachineIds.of("Colina 27")))
            .thenReturn(new AuthorizeResult(true, false, true, "Client key authorized on nas-borg"));

        ResponseEntity<BackupRestController.AuthorizeResponse> response =
            controller.authorizeClient("nas-borg", TestMachineIds.of("Colina 27").value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().authorized()).isTrue();
        assertThat(response.getBody().alreadyTrusted()).isFalse();
        // Slice 8: the host-key pin outcome is surfaced to the UI.
        assertThat(response.getBody().hostKeyPinned()).isTrue();
        verify(authorizeBackupClient).authorizeClient("nas-borg", TestMachineIds.of("Colina 27"));
    }

    @Test
    void authorizeUnknownServerReturns404() {
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.AuthorizeResponse> response =
            controller.authorizeClient("nope", TestMachineIds.of("Colina 27").value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(authorizeBackupClient, never()).authorizeClient(any(), any());
    }

    @Test
    void authorizeUnknownMachineReturns404() {
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of());

        ResponseEntity<BackupRestController.AuthorizeResponse> response =
            controller.authorizeClient("nas-borg", TestMachineIds.of("ghost").value());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(authorizeBackupClient, never()).authorizeClient(any(), any());
    }

    // --- Just select and back up: protected paths ---

    /**
     * The regression that broke ticking a folder. The browser was moved onto machine identities in
     * `126038b`, so it sends `/machines/<uuid>/backup/paths` — and this controller went on resolving that
     * segment as a NAME, so every protect and unprotect answered 404. The Explorer showed nothing: the
     * checkbox simply did not stick.
     *
     * <p>A name here is now what 404s, which is the point: an identity is the only thing that addresses a
     * machine, and a name in this position would be a lookup that duplicates make ambiguous.
     */
    @Test
    void protectPaths_addressesTheMachineByIdentity_notByName() {
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null, ""));

        ResponseEntity<BackupRestController.ProtectPathsResponse> byId = controller.protectPaths(
            TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobs.getByMachine(COLINA)).isNotEmpty();

        assertThat(controller.protectPaths("Colina 27",
            new BackupRestController.ProtectPathsRequest(List.of("/etc"))).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void protectPathsCreatesTheRepositoryAndJobThenReturnsTheJob() {
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null, "Preparing client on Colina 27"));

        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir", "/etc/nginx")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().machineName()).isEqualTo("Colina 27");
        assertThat(response.getBody().sourcePaths()).containsExactlyInAnyOrder("/home/geir", "/etc/nginx");

        // A repository was created for the machine — named after its identity — with a strong,
        // backend-owned passphrase (never taken from the client).
        BackupRepository createdRepo = repositories.getByName(COLINA.value()).orElseThrow();
        assertThat(createdRepo.serverName()).isEqualTo("nas-borg");
        assertThat(createdRepo.passphrase()).matches("[A-Za-z0-9]{32}");
        // The job references that repository.
        assertThat(jobs.getByMachine(COLINA)).singleElement()
            .extracting(BackupJob::repositoryName).isEqualTo(COLINA.value());
    }

    @Test
    void protectPathsAddsToAnExistingJobAndNormalizesDescendants() {
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(job()); // machineName "Colina 27", sourcePaths ["/home/geir"]
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        // A descendant of an already-protected path is a no-op; a genuinely new path is added.
        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir/docs", "/var/lib/docker")));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sourcePaths())
            .containsExactlyInAnyOrder("/home/geir", "/var/lib/docker");
        // No second repository was created — the existing one is reused.
        assertThat(repositories.getAll()).hasSize(1);
    }

    // --- First back-up auto-readies the host via the driven port (the operator never runs the wizard) ---

    @Test
    void protectPathsOnAMachineWithNoPriorJobReadiesTheHostAndCarriesTheOutcome() {
        // A machine's FIRST back-up (no prior job) readies the host automatically through the ForReadyingBackup
        // Clients driven port — the decision is the domain's, and its outcome rides back on the provisioning
        // object of the response.
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null,
                "Preparing client on Colina 27 — you'll be notified when it finishes."));

        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().provisioning()).isNotNull();
        assertThat(response.getBody().provisioning().started()).isTrue();
        assertThat(response.getBody().provisioning().scriptOnly()).isFalse();
        assertThat(response.getBody().provisioning().stagedScriptPath()).isNull();
        assertThat(response.getBody().provisioning().message()).contains("Preparing client");
        verify(readier).readyForBackup(TestMachineIds.of("Colina 27"));
    }

    @Test
    void protectPathsOnAMachineThatAlreadyHasAJobDoesNotReadyAgain() {
        // Adding paths to an existing job must NEVER re-ready — the host is already provisioned. The response
        // carries no provisioning object and the port is never called.
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(job()); // machineName "Colina 27" already has a job
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/var/lib/docker")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().provisioning()).isNull();
        verify(readier, never()).readyForBackup(any());
    }

    @Test
    void protectPathsReadyingFailureSurfacesInTheOutcomeButKeepsTheSavedPaths() {
        // The port never throws (a readying failure comes back as a reasoned outcome), so the back-up still
        // succeeds and the paths are saved; the failure reason rides on the provisioning object.
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(false, false, null,
                "Automatic provisioning could not run: ssh: connection reset"));

        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        // The paths were still saved despite readying reporting a failure.
        assertThat(response.getBody().sourcePaths()).containsExactly("/home/geir");
        assertThat(response.getBody().provisioning()).isNotNull();
        assertThat(response.getBody().provisioning().started()).isFalse();
        assertThat(response.getBody().provisioning().message()).isNotBlank();
    }

    @Test
    void protectPathsUnknownMachineReturns404() {
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of());

        ResponseEntity<BackupRestController.ProtectPathsResponse> response = controller.protectPaths(TestMachineIds.of("ghost").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jobs.getAll()).isEmpty();
    }

    @Test
    void protectPathsWithNoBackupServerConflicts() {
        // No server designated yet -> the operation cannot create a repository. Surfaces as a 409 via
        // ConflictException.
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.protectPaths(TestMachineIds.of("Colina 27").value(),
                new BackupRestController.ProtectPathsRequest(List.of("/home/geir"))))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Designate a backup server");
        assertThat(jobs.getAll()).isEmpty();
    }

    @Test
    void unprotectPathsRemovesSomeAndReturnsTheUpdatedJob() {
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir", "/etc/nginx"), List.of(), 7, 4, 6, "zstd,6", true, false));
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("Colina 27").value(), new BackupRestController.ProtectPathsRequest(List.of("/etc/nginx")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().changed()).isTrue();
        assertThat(response.getBody().stopped()).containsExactly("/etc/nginx");
        assertThat(response.getBody().job().sourcePaths()).containsExactly("/home/geir");
    }

    @Test
    void unprotectPathsInsideAStillProtectedFolderReportsTheExcludeItRecorded() {
        // The reported bug at the endpoint: /home stays protected and the logs folder becomes an exclude. The
        // response must show the change — the same call used to answer 200 with a completely untouched job.
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home"), List.of(), 7, 4, 6, "zstd,6", true, false));
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("Colina 27").value(),
            new BackupRestController.ProtectPathsRequest(List.of("/home/openhab/userdata/logs")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().changed()).isTrue();
        assertThat(response.getBody().stopped()).containsExactly("/home/openhab/userdata/logs");
        assertThat(response.getBody().job().sourcePaths()).containsExactly("/home");
        assertThat(response.getBody().job().excludes()).containsExactly("/home/openhab/userdata/logs");
    }

    @Test
    void unprotectPathsThatChangedNothingSaysSo_ratherThanReadingAsARemoval() {
        // Nothing on the machine protects /var/log. A 200 with an unchanged job used to be indistinguishable
        // from a successful removal, and the browser reported one. The outcome now says changed: false.
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(job()); // only "/home/geir"
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("Colina 27").value(), new BackupRestController.ProtectPathsRequest(List.of("/var/log")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().changed()).isFalse();
        assertThat(response.getBody().stopped()).isEmpty();
        assertThat(response.getBody().job().sourcePaths()).containsExactly("/home/geir");
    }

    @Test
    void unprotectPathsRemovingTheLastPathDeletesTheJobAndReturns204() {
        backupServers.save(server());
        repositories.save(repo());
        jobs.save(job()); // only "/home/geir"
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("Colina 27").value(), new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jobs.getByName("colina-home")).isEmpty();
        // The repository is left intact.
        assertThat(repositories.getByName("nas-borg")).isPresent();
    }

    @Test
    void unprotectPathsWhenMachineHasNoJobChangedNothing_andDoesNotPretendOtherwise() {
        // Nothing is backed up on this machine at all, so nothing stopped. It is not an error — but it is not
        // a removal either, so it comes back as an explicit "changed: false" instead of a bare 204.
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("Colina 27").value(), new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().changed()).isFalse();
        assertThat(response.getBody().job()).isNull();
    }

    @Test
    void unprotectPathsUnknownMachineReturns404() {
        when(getMachines.getAllMachines()).thenReturn(List.of());

        ResponseEntity<BackupRestController.UnprotectPathsResponse> response = controller.unprotectPaths(
            TestMachineIds.of("ghost").value(), new BackupRestController.ProtectPathsRequest(List.of("/home/geir")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A job is stored under the identity the request carried, with no interpretation on the way in. This
     * used to be a lookup by NAME that had to match the uniqueness guard's rule character for character —
     * "colina 27" finding "Colina 27" — because a name rejected at creation for colliding with one the
     * lookup then could not find is the worst of both. An identity has no case and no whitespace to be
     * lenient about, so the rule it had to agree with is simply gone.
     */
    @Test
    void saveJobStoresTheJobUnderTheIdentityTheRequestCarried() {
        repositories.save(repo());
        backupServers.save(server());
        when(getMachines.getAllMachines()).thenReturn(List.of(machine("Colina 27")));

        ResponseEntity<BackupRestController.JobResponse> response = controller.saveJob(COLINA.value(),
            new BackupRestController.JobRequest("nas-borg",
                List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobs.getByMachine(COLINA)).singleElement()
            .extracting(BackupJob::machineId).isEqualTo(COLINA);
    }

    // --- what a store is called on screen -----------------------------------------------------------

    @Test
    void repositoriesCarryAReadableLabel_sinceTheirNameIsAnIdentityNow() {
        // A repository is named after the machine's identity, so its own name says nothing to a person.
        // The label is the domain's answer (BackupStoreLabel), resolved here at the driving edge — the
        // browser must not re-derive it, or two surfaces eventually disagree about which store is which.
        Machine colina = machine("Colina 27");
        repositories.save(new BackupRepository(colina.id().value(), "nas-borg", null, "s3cr3t", false));
        jobs.save(new BackupJob("Colina 27", colina.id(), colina.id().value(),
            List.of("/home"), List.of(), 7, 4, 6, "zstd,6", true, false));
        when(getMachines.getAllMachines()).thenReturn(List.of(colina));

        var body = controller.listRepositories().getBody();

        assertThat(body).singleElement()
            .extracting(BackupRestController.RepositoryResponse::label).isEqualTo("Colina 27");
    }

    @Test
    void aStoreWhoseMachineIsGone_saysSoRatherThanShowingABareIdentity() {
        // The orphan case, and the reason it matters more now: the directory is a UUID, so an operator
        // reading the list has nothing else to go on. It must not look like a machine that is simply
        // called something odd.
        MachineId departed = TestMachineIds.of("departed");
        repositories.save(new BackupRepository(departed.value(), "nas-borg", null, "s3cr3t", false));
        when(getMachines.getAllMachines()).thenReturn(List.of());

        var body = controller.listRepositories().getBody();

        assertThat(body).singleElement()
            .extracting(BackupRestController.RepositoryResponse::label).asString()
            .contains("no longer in this fleet").contains(departed.value());
    }

    // --- survival kit ------------------------------------------------------------------------------------

    /**
     * One call, one decision: make sure the fleet's backups can still be read if this server is gone. The
     * response carries Vaier's reasoning — which hosts hold a copy and why — because the operator is meant to
     * be able to disagree with a stated reason, not to derive one.
     */
    @Test
    void writeSurvivalKit_reportsWhereTheCopiesWentAndWhy() {
        MachineId apalveien = TestMachineIds.of("Apalveien 5");
        when(writeSurvivalKit.writeSurvivalKit()).thenReturn(new SurvivalKitReport(
            new SurvivalKitHosts.Selection(
                List.of(new SurvivalKitHosts.Placement(apalveien, "Apalveien 5", "site-a", "furthest from the rest")),
                List.of(new SurvivalKitHosts.Skipped("geir-pc", "a laptop is not always on"))),
            new SurvivalKitRollout.Result(1,
                List.of(new SurvivalKitRollout.Failure("Colina 27", "ssh: connection reset")))));

        ResponseEntity<BackupRestController.SurvivalKitResponse> response = controller.writeSurvivalKit();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().copiesKept()).isEqualTo(1);
        assertThat(response.getBody().survivesLossOfVaier()).isTrue();
        // Three copies were intended and one landed, which the operator has to be told outright.
        assertThat(response.getBody().fewerCopiesThanIntended()).isTrue();
        assertThat(response.getBody().chosen()).singleElement()
            .satisfies(p -> {
                assertThat(p.machineId()).isEqualTo(apalveien.value());
                assertThat(p.machineName()).isEqualTo("Apalveien 5");
                assertThat(p.reason()).isNotBlank();
            });
        assertThat(response.getBody().skipped()).singleElement()
            .satisfies(sk -> assertThat(sk.reason()).contains("always on"));
        assertThat(response.getBody().failures()).singleElement()
            .satisfies(f -> assertThat(f.machineName()).isEqualTo("Colina 27"));
    }

    /**
     * No passphrase is a state conflict, not a server error: {@code 409} carrying the message, so the page
     * can say what to do instead of showing a masked 500.
     */
    @Test
    void writeSurvivalKit_returns409WhenNoPassphraseHasBeenChosen() {
        when(writeSurvivalKit.writeSurvivalKit())
            .thenThrow(new IllegalStateException("Choose a survival kit passphrase before writing a kit"));

        ResponseEntity<BackupRestController.SurvivalKitResponse> response = controller.writeSurvivalKit();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
