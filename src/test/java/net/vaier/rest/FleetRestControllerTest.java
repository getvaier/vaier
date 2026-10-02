package net.vaier.rest;

import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetReverseProxyAuditUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.InspectConsoleCertificateUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.ConsoleCertificate;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.DockerService;
import net.vaier.domain.FleetNudge;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.Reachability;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.UpdateAvailability;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Needs you, composed at the driving edge from what the use cases already hold and judged by the domain. */
class FleetRestControllerTest {

    private final GetMachinesUseCase getMachines = mock(GetMachinesUseCase.class);
    private final GetPublishableServicesUseCase getPublishable = mock(GetPublishableServicesUseCase.class);
    private final GetPublishedServicesUseCase getPublished = mock(GetPublishedServicesUseCase.class);
    private final GetBackupServersUseCase getBackupServers = mock(GetBackupServersUseCase.class);
    private final GetBackupRepositoriesUseCase getRepositories = mock(GetBackupRepositoriesUseCase.class);
    private final ListAccessEntriesUseCase listAccessEntries = mock(ListAccessEntriesUseCase.class);
    private final GetAppSettingsUseCase getAppSettings = mock(GetAppSettingsUseCase.class);
    private final GetVaierServerUseCase getVaierServer = mock(GetVaierServerUseCase.class);
    private final GetLanServerReachabilityUseCase reachability = mock(GetLanServerReachabilityUseCase.class);
    private final GetBackupJobsUseCase getBackupJobs = mock(GetBackupJobsUseCase.class);
    private final GetBackupRunsUseCase getBackupRuns = mock(GetBackupRunsUseCase.class);
    private final GetMachineDiskStandingsUseCase getDiskStandings = mock(GetMachineDiskStandingsUseCase.class);
    private final DiscoverPeerContainersUseCase peerContainers = mock(DiscoverPeerContainersUseCase.class);
    private final DiscoverVaierServerContainersUseCase serverContainers =
        mock(DiscoverVaierServerContainersUseCase.class);
    private final GetLanServerScrapeUseCase lanContainers = mock(GetLanServerScrapeUseCase.class);
    private final ListEnrolmentRequestsUseCase enrolments = mock(ListEnrolmentRequestsUseCase.class);
    private final GetReverseProxyAuditUseCase audit = mock(GetReverseProxyAuditUseCase.class);
    private final InspectConsoleCertificateUseCase certificate = mock(InspectConsoleCertificateUseCase.class);
    private final GetVpnClientsUseCase vpnClients = mock(GetVpnClientsUseCase.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC);

    private final FleetRestController controller = new FleetRestController(getMachines, getPublishable,
        getPublished, getBackupServers, getRepositories, listAccessEntries, getAppSettings, getVaierServer,
        reachability, getBackupJobs, getBackupRuns, getDiskStandings, peerContainers, serverContainers,
        lanContainers, enrolments, audit, certificate, vpnClients, clock);

    @Test
    void needs_gathersEverySignal_andHandsTheDomainsVerdictThrough() {
        Machine server = Machine.vaierServer(MachineId.generate(), null);
        Machine printer = new Machine(MachineId.generate(), "Printer", MachineType.LAN_SERVER, null, null, null,
            null, null, null, null, null, "192.168.3.20", false, null, DeviceCategory.PRINTER, null);
        when(getMachines.getAllMachines()).thenReturn(List.of(server, printer));
        when(getVaierServer.getVaierServerMachine()).thenReturn(server);
        when(reachability.getReachability("192.168.3.20")).thenReturn(Reachability.DOWN);
        BackupJob job = new BackupJob("Printer", printer.id(), "repo", List.of("/"), List.of(), 7, 4, 6, "zstd,6",
            true, false);
        when(getBackupJobs.getBackupJobs()).thenReturn(List.of(job));
        when(getBackupRuns.latestForMachine(printer.id()))
            .thenReturn(Optional.of(BackupRun.failed(job, "r", clock.instant(), "Could not reach it")));
        when(getAppSettings.getSettings()).thenReturn(new AppSettingsResult("example.com", null, null, null, null,
            null, null, null, null, null, 80, false, 3, "Europe/Oslo", false, false, true, true));
        when(certificate.inspectConsoleCertificate("vaier.example.com")).thenReturn(
            Optional.of(new ConsoleCertificate("R11", clock.instant().plus(Duration.ofDays(60)), false)));
        when(vpnClients.getClients()).thenThrow(new RuntimeException("wg command failed"));
        when(audit.getReverseProxyAudit()).thenReturn(new ReverseProxyAudit(List.of()));
        // The Vaier server's own containers arrive with no machine on them; the edge files them under it.
        when(serverContainers.discover()).thenReturn(List.of(new DockerService("1", "traefik", "traefik", "3",
            List.of(), List.of(), "running", "sha256:a", UpdateAvailability.UPDATE_AVAILABLE)));

        List<FleetRestController.FleetNudgeResponse> body = controller.needs();

        assertThat(body).extracting(FleetRestController.FleetNudgeResponse::kind).containsExactly(
            FleetNudge.Kind.VAIER_BASICS.name(), FleetNudge.Kind.MACHINE_DOWN.name(),
            FleetNudge.Kind.DESIGNATE_BACKUP_SERVER.name(), FleetNudge.Kind.IMAGE_UPDATES.name());
        assertThat(body.get(1).value()).isEqualTo(printer.id().value());
        assertThat(body.get(1).trouble()).isTrue();
        assertThat(body.get(1).tone()).isEqualTo(FleetNudge.Tone.VERDICT.name());
        assertThat(body.get(1).evidence()).endsWith("its last backup failed too");
        assertThat(body.get(3).value()).isEqualTo(server.id().value());
    }
}
