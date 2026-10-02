package net.vaier.rest;

import lombok.RequiredArgsConstructor;
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
import net.vaier.domain.BackupFleet;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.ConsoleCertificate;
import net.vaier.domain.DockerService;
import net.vaier.domain.FleetNudge;
import net.vaier.domain.FleetNudges;
import net.vaier.domain.FleetSignals;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.PreFlight;
import net.vaier.domain.PreFlightFacts;
import net.vaier.domain.Reachability;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fleet root's own read: <b>Needs you</b>. Composed at the driving edge — this controller gathers each
 * signal from an existing {@code *UseCase} and hands them to the pure-domain {@link FleetNudges} assembler, which
 * owns every "should we say this?", the wording and the order. No application service reaches across domains to
 * collect them.
 */
@RestController
@RequestMapping("/fleet")
@RequiredArgsConstructor
public class FleetRestController {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final GetBackupServersUseCase getBackupServersUseCase;
    private final GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;
    private final ListAccessEntriesUseCase listAccessEntriesUseCase;
    private final GetAppSettingsUseCase getAppSettingsUseCase;
    private final GetVaierServerUseCase getVaierServerUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRunsUseCase getBackupRunsUseCase;
    private final GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final GetReverseProxyAuditUseCase getReverseProxyAuditUseCase;
    private final InspectConsoleCertificateUseCase inspectConsoleCertificateUseCase;
    private final GetVpnClientsUseCase getVpnClientsUseCase;
    private final Clock clock;

    @GetMapping("/needs")
    public List<FleetNudgeResponse> needs() {
        AppSettingsResult settings = getAppSettingsUseCase.getSettings();
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        MachineId vaierServer = getVaierServerUseCase.getVaierServerMachine().id();
        List<MachineDiskStanding> disks = getMachineDiskStandingsUseCase.getMachineDiskStandings();

        Map<String, Reachability> reachability = new HashMap<>();
        Map<String, Long> lastSeen = new HashMap<>();
        machines.stream().map(Machine::lanAddress).filter(address -> address != null).forEach(address -> {
            reachability.put(address, getLanServerReachabilityUseCase.getReachability(address));
            Long seen = getLanServerReachabilityUseCase.getLastSeenEpochSec(address);
            if (seen != null) lastSeen.put(address, seen);
        });

        List<BackupJob> jobs = getBackupJobsUseCase.getBackupJobs();
        List<BackupRun> runs = jobs.stream()
            .flatMap(job -> getBackupRunsUseCase.latestForMachine(job.machineId()).stream())
            .toList();

        FleetSignals signals = FleetSignals.builder()
            .machines(machines)
            .publishable(getPublishableServicesUseCase.getPublishableServices())
            .publishedCount(getPublishedServicesUseCase.getPublishedServices().size())
            .fleet(new BackupFleet(getBackupServersUseCase.getBackupServers()))
            .repositoryCount(getBackupRepositoriesUseCase.getBackupRepositories().size())
            .survivalKitWritten(settings.survivalKitWritten())
            .accessEntries(listAccessEntriesUseCase.listAccessEntries())
            .smtpConfigured(settings.smtpConfigured())
            .vaierServer(vaierServer)
            .lanReachability(reachability)
            .lanLastSeen(lastSeen)
            .backupJobs(jobs)
            .latestRuns(runs)
            .diskStandings(disks)
            .containers(containersByMachine(vaierServer))
            .enrolmentRequests(listEnrolmentRequestsUseCase.pending())
            .preFlight(preFlight(settings, vaierServer, disks))
            .routeAudit(getReverseProxyAuditUseCase.getReverseProxyAudit())
            .zone(clock.getZone())
            .build();
        return FleetNudges.forFleet(signals).stream().map(FleetNudgeResponse::from).toList();
    }

    /** The containers Vaier last scraped, filed by machine; the Vaier server's own arrive with no machine on them. */
    private Map<String, List<DockerService>> containersByMachine(MachineId vaierServer) {
        Map<String, List<DockerService>> containers = new HashMap<>();
        for (PeerContainers peer : discoverPeerContainersUseCase.discoverAll()) {
            if (peer.machineId() != null && peer.containers() != null) {
                containers.put(peer.machineId(), peer.containers());
            }
        }
        for (LanServerContainers lan : getLanServerScrapeUseCase.getLanServerContainers()) {
            if (lan.machineId() != null && lan.containers() != null) {
                containers.put(lan.machineId(), lan.containers());
            }
        }
        containers.put(vaierServer.value(), discoverVaierServerContainersUseCase.discover());
        return containers;
    }

    /** "Is it working?" (#265), from what the use cases already hold; WireGuard is judged by whether it answers. */
    private PreFlight preFlight(AppSettingsResult settings, MachineId vaierServer, List<MachineDiskStanding> disks) {
        String consoleHost = new VaierHostnames(settings.domain()).configuredVaierServerFqdn().orElse(null);
        Optional<ConsoleCertificate> certificate = consoleHost == null ? Optional.empty()
            : inspectConsoleCertificateUseCase.inspectConsoleCertificate(consoleHost);
        boolean wireguardAnswering;
        try {
            getVpnClientsUseCase.getClients();
            wireguardAnswering = true;
        } catch (RuntimeException e) {
            wireguardAnswering = false;
        }
        Optional<MachineDiskStanding> serverDisk = disks.stream()
            .filter(d -> vaierServer.isSameAs(d.machineId()))
            .findFirst();
        return PreFlight.of(new PreFlightFacts(consoleHost, settings.wildcardDnsSeverity(),
            settings.wildcardDnsMessage(), certificate, wireguardAnswering, serverDisk, clock.instant()));
    }

    /**
     * One row flattened for the browser. {@code value} is the machine it points at, where it points at one;
     * {@code trouble} says it is a verdict on that machine; {@code tone} how loud the row is; {@code detail} is
     * what the row can open.
     */
    public record FleetNudgeResponse(String kind, String title, String evidence, String action, String value,
                                     boolean trouble, String tone, List<String> detail) {
        static FleetNudgeResponse from(FleetNudge n) {
            return new FleetNudgeResponse(n.kind().name(), n.title(), n.evidence(), n.action(), n.value(),
                n.kind().isTrouble(), n.kind().tone().name(), n.detail());
        }
    }
}
