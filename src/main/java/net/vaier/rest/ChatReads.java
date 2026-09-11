package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.ForgetUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.ReadWebPageUseCase;
import net.vaier.application.RememberUseCase;
import net.vaier.application.RunReadOnlyCommandUseCase;
import net.vaier.application.SearchWebUseCase;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.ChatTool;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.DockerService;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineReference;
import net.vaier.domain.MachineType;
import net.vaier.domain.Memory;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Reachability;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Every read Marvin may make, wired to the projection that answers it (#360). It is a driving-side helper
 * like the controller that uses it — not a {@code *Service}: it holds no state, decides nothing, and its
 * whole job is to turn a {@code *UseCase}'s answer into a few lines of JSON a model can read.
 *
 * <p>It exists because two things now need the same reads: a question somebody is waiting on, and an
 * <b>errand</b> running with nobody watching. Which reads those are is {@link ChatTool#whileNobodyIsWatching}'s
 * decision — the domain's, so the tools an errand is offered and the tools its prompt describes can never
 * disagree. The four left out need somebody there, and the controller keeps them.
 *
 * <p>The constructor is the point, as the controller's was: every read Chat may make is a {@code *UseCase}
 * named in it, so what the model can be told about this fleet is a list anyone can review — and it is a list
 * of <em>reads</em>. There is no verb here that runs on the model's say-so; the one use case that reaches a
 * machine runs a <b>Read-only command</b>, and what counts as one is the domain's decision.
 *
 * <p>The projections below are the whole of what leaves Vaier for the Claude API, and they are deliberately
 * small: what a person would say out loud about a machine, a service or a backup. No key, no preshared key,
 * no config text, no credential, no passphrase, no token and no <b>Enrolment ticket</b> is in any of them,
 * and {@code ChatReadsTest} reads every one of them back to prove it.
 */
@Component
@Slf4j
public class ChatReads {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVpnPeersUseCase getVpnPeersUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRunsUseCase getBackupRunsUseCase;
    private final GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    private final RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;
    private final SearchWebUseCase searchWebUseCase;
    private final ReadWebPageUseCase readWebPageUseCase;
    private final RememberUseCase rememberUseCase;
    private final ForgetUseCase forgetUseCase;
    private final ObjectMapper objectMapper;

    public ChatReads(GetMachinesUseCase getMachinesUseCase,
                     GetVpnPeersUseCase getVpnPeersUseCase,
                     GetLanServerReachabilityUseCase getLanServerReachabilityUseCase,
                     ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase,
                     GetPublishedServicesUseCase getPublishedServicesUseCase,
                     GetBackupJobsUseCase getBackupJobsUseCase,
                     GetBackupRunsUseCase getBackupRunsUseCase,
                     GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase,
                     DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                     DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                     GetBlockDecisionsUseCase getBlockDecisionsUseCase,
                     RunReadOnlyCommandUseCase runReadOnlyCommandUseCase,
                     SearchWebUseCase searchWebUseCase,
                     ReadWebPageUseCase readWebPageUseCase,
                     RememberUseCase rememberUseCase,
                     ForgetUseCase forgetUseCase,
                     ObjectMapper objectMapper) {
        this.getMachinesUseCase = getMachinesUseCase;
        this.getVpnPeersUseCase = getVpnPeersUseCase;
        this.getLanServerReachabilityUseCase = getLanServerReachabilityUseCase;
        this.listEnrolmentRequestsUseCase = listEnrolmentRequestsUseCase;
        this.getPublishedServicesUseCase = getPublishedServicesUseCase;
        this.getBackupJobsUseCase = getBackupJobsUseCase;
        this.getBackupRunsUseCase = getBackupRunsUseCase;
        this.getMachineDiskStandingsUseCase = getMachineDiskStandingsUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getBlockDecisionsUseCase = getBlockDecisionsUseCase;
        this.runReadOnlyCommandUseCase = runReadOnlyCommandUseCase;
        this.searchWebUseCase = searchWebUseCase;
        this.readWebPageUseCase = readWebPageUseCase;
        this.rememberUseCase = rememberUseCase;
        this.forgetUseCase = forgetUseCase;
        this.objectMapper = objectMapper;
    }

    /**
     * One offer per read Marvin may make alone, in the catalogue's own order. Nothing here sends anything to
     * a pane: an errand has none, and a question's own controller wraps these to announce them.
     */
    public List<ToolOffer> offers() {
        Map<ChatTool, Function<Map<String, String>, String>> reads = new HashMap<>();
        reads.put(ChatTool.FLEET, arguments -> readFleet());
        reads.put(ChatTool.WAITING_TO_JOIN, arguments -> readWaitingToJoin());
        reads.put(ChatTool.PUBLISHED_SERVICES, arguments -> readPublishedServices());
        reads.put(ChatTool.BACKUPS, arguments -> readBackups());
        reads.put(ChatTool.DISKS, arguments -> readDisks());
        reads.put(ChatTool.CONTAINER_UPDATES, arguments -> readContainerUpdates());
        reads.put(ChatTool.SECURITY, arguments -> readSecurity());
        reads.put(ChatTool.RUN_ON_MACHINE, this::readRunOnMachine);
        reads.put(ChatTool.SEARCH_WEB, this::searchWeb);
        reads.put(ChatTool.READ_WEB_PAGE, this::readWebPage);
        reads.put(ChatTool.REMEMBER, this::remember);
        reads.put(ChatTool.FORGET, this::forget);

        List<ToolOffer> offers = new ArrayList<>();
        for (ChatTool tool : ChatTool.whileNobodyIsWatching()) {
            offers.add(new ToolOffer(tool, reads.get(tool)));
        }
        return offers;
    }

    private String readFleet() {
        Map<String, VpnPeerView> peers = new HashMap<>();
        for (VpnPeerView peer : getVpnPeersUseCase.getVpnPeers()) {
            if (peer.machineId() != null) {
                peers.put(peer.machineId(), peer);
            }
        }
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        // What "reachable" means differs by kind of machine — the tunnel for a peer, the cached LAN probe for
        // a LAN server, always for the Vaier server — and the machine itself already knows. It reads the LAN
        // signal from the same cache the Explorer does; nothing is probed to answer a question.
        Map<String, Reachability> lan = new HashMap<>();
        for (Machine machine : machines) {
            if (machine.type() == MachineType.LAN_SERVER && machine.lanAddress() != null) {
                lan.put(machine.lanAddress(), getLanServerReachabilityUseCase.getReachability(machine.lanAddress()));
            }
        }
        return asJson(machines.stream()
            .map(machine -> MachineFact.of(machine, peers.get(machine.id().value()), standingOf(machine, lan)))
            .toList());
    }

    private String readWaitingToJoin() {
        long now = System.currentTimeMillis();
        return asJson(listEnrolmentRequestsUseCase.pending().stream()
            .map(request -> WaitingPhoneFact.of(request, now))
            .toList());
    }

    private String readPublishedServices() {
        return asJson(getPublishedServicesUseCase.getPublishedServices().stream()
            .map(ServiceFact::of)
            .toList());
    }

    private String readBackups() {
        Map<String, String> names = machineNames();
        return asJson(getBackupJobsUseCase.getBackupJobs().stream()
            .map(job -> BackupFact.of(job, names.get(job.machineId().value()),
                getBackupRunsUseCase.latestForMachine(job.machineId())))
            .toList());
    }

    private String readDisks() {
        Map<String, String> names = machineNames();
        return asJson(getMachineDiskStandingsUseCase.getMachineDiskStandings().stream()
            .map(standing -> DiskFact.of(standing, names.get(standing.machineId().value())))
            .toList());
    }

    /** Only what wants pulling. A list of every container the fleet runs would answer a different question. */
    private String readContainerUpdates() {
        Map<String, String> names = machineNames();
        List<ContainerUpdateFact> wanting = new ArrayList<>();
        for (PeerContainers peer : discoverPeerContainersUseCase.discoverAll()) {
            String machine = names.getOrDefault(peer.machineId(), peer.peerId());
            wanting.addAll(outdated(machine, peer.containers()));
        }
        wanting.addAll(outdated("the Vaier server", discoverVaierServerContainersUseCase.discover()));
        return asJson(wanting);
    }

    private static List<ContainerUpdateFact> outdated(String machine, List<DockerService> containers) {
        return containers == null ? List.of() : containers.stream()
            .filter(container -> container.updateAvailable().isUpdateAvailable())
            .map(container -> ContainerUpdateFact.of(machine, container))
            .toList();
    }

    private String readSecurity() {
        return asJson(getBlockDecisionsUseCase.getBlockDecisions().stream()
            .map(BlockFact::of)
            .toList());
    }

    /**
     * One command on the machine the model named. Every way this can fail is answered in a sentence the
     * model can repeat: the domain's own refusal verbatim, a name no machine has, a machine Vaier holds no
     * login for. A transport failure's own message can carry an address, a user or a path, so that one is
     * said in Vaier's words — the same reason the emitter never repeats one.
     */
    private String readRunOnMachine(Map<String, String> arguments) {
        Machine machine;
        try {
            machine = new MachineReference(arguments.get("machine")).resolve(getMachinesUseCase.getAllMachines());
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        String command = arguments.getOrDefault("command", "");
        try {
            CommandOutcome outcome = runReadOnlyCommandUseCase.runReadOnly(machine.id(), command);
            return asJson(CommandFact.of(machine, command, outcome));
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (NoHostCredentialException e) {
            return "No SSH credential is stored for " + machine.name() + ", so Vaier cannot run anything there.";
        } catch (RuntimeException e) {
            log.warn("Chat could not run a command on {}: {}", machine.name(), e.toString());
            return machine.name() + " could not be reached over SSH.";
        }
    }

    /**
     * The <b>web read</b>. Both halves refuse in the domain's own words — a query it would not send, an
     * address off the public internet — and both say an unexpected failure in Vaier's words instead of its
     * own: a transport failure's message carries proxies, ports and certificate chains, and it goes to the
     * model.
     */
    private String searchWeb(Map<String, String> arguments) {
        try {
            return searchWebUseCase.search(arguments.get("query")).forModel();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (RuntimeException e) {
            log.warn("Chat could not search the web: {}", e.toString());
            return "Vaier could not search just now.";
        }
    }

    private String readWebPage(Map<String, String> arguments) {
        try {
            return readWebPageUseCase.read(arguments.get("url")).toolResult();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (RuntimeException e) {
            log.warn("Chat could not read a web page: {}", e.toString());
            return "Vaier could not read that.";
        }
    }

    /** One fact kept; the domain's refusal is the answer when it is not one. */
    private String remember(Map<String, String> arguments) {
        try {
            Memory.Fact fact = rememberUseCase.remember(arguments.get("fact"));
            return "Remembered [" + fact.id() + "]: " + fact.text();
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    private String forget(Map<String, String> arguments) {
        try {
            forgetUseCase.forget(arguments.getOrDefault("id", "").trim());
            return "Forgotten.";
        } catch (NotFoundException | IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    /** Machine identities to the names the Explorer shows, so every projection says what is on screen. */
    private Map<String, String> machineNames() {
        Map<String, String> names = new HashMap<>();
        for (Machine machine : getMachinesUseCase.getAllMachines()) {
            names.put(machine.id().value(), machine.name());
        }
        return names;
    }

    private String asJson(Object projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            log.warn("Chat could not render a tool result", e);
            return "Vaier could not read that.";
        }
    }

    // --- what the model is told -------------------------------------------------------------------------

    /**
     * A machine as a person would describe it. No public key, no allowed IPs, no endpoint.
     *
     * <p>The tunnel address comes from the peer view, which the domain already derived — never re-read off
     * {@code allowedIps} here; a LAN server has no peer and so no tunnel address. Whether the machine is
     * reachable is the machine's own verdict ({@link Machine#isReachable}), so a LAN server or the Vaier
     * server is never called "not connected" for lacking a tunnel it was never meant to have.
     */
    record MachineFact(String id, String name, String type, String tunnelIp, String lanCidr, String standing) {
        static MachineFact of(Machine machine, VpnPeerView peer, String standing) {
            return new MachineFact(machine.id() == null ? null : machine.id().value(), machine.name(),
                machine.type().name(),
                peer == null ? null : peer.tunnelIp(), machine.lanCidr(), standing);
        }
    }

    /**
     * The machine's verdict on itself, in a word the model can repeat. A LAN server that has not been probed
     * yet — the minutes after a restart — is "not checked yet", never "unreachable": the fact is missing,
     * not bad, and the difference is exactly what an operator asks about.
     */
    private static String standingOf(Machine machine, Map<String, Reachability> lan) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(machine.name())) return "this server, always reachable";
        if (machine.type() == MachineType.LAN_SERVER) {
            Reachability r = lan.getOrDefault(machine.lanAddress(), Reachability.UNKNOWN);
            return r == Reachability.OK ? "reachable" : r == Reachability.DOWN ? "unreachable" : "not checked yet";
        }
        return machine.isReachable(lan) ? "connected" : "not connected";
    }

    /** A phone waiting to be let in: its name, its join code, and how long it has. Never its ticket or key. */
    record WaitingPhoneFact(String name, String joinCode, long minutesLeft) {
        static WaitingPhoneFact of(EnrolmentRequest request, long nowEpochMs) {
            return new WaitingPhoneFact(request.name(), request.code(),
                Math.round(request.secondsLeft(nowEpochMs) / 60.0));
        }
    }

    record ServiceFact(String name, String machine, String address, boolean reachable) {
        static ServiceFact of(PublishedServiceUco service) {
            return new ServiceFact(service.shortName(), service.hostName(), service.dnsAddress(),
                service.healthy());
        }
    }

    record BackupFact(String job, String machine, boolean enabled, String lastRun, String lastRunAt,
                      String lastRunNote) {
        static BackupFact of(BackupJob job, String machine, Optional<BackupRun> latest) {
            return new BackupFact(job.name(), machine, job.enabled(),
                latest.map(run -> run.status().name()).orElse("never run"),
                latest.map(BackupRun::finishedAt).map(String::valueOf).orElse(null),
                latest.map(BackupRun::summary).orElse(null));
        }
    }

    record DiskFact(String machine, String fullestFilesystem, int usedPercent, int alertsAbovePercent,
                    int filesystemsOverTheirThreshold) {
        static DiskFact of(MachineDiskStanding standing, String machine) {
            return new DiskFact(machine, standing.worstMountPoint(), standing.worstUsedPercent(),
                standing.worstThresholdPercent(), standing.breachingFilesystems());
        }
    }

    record ContainerUpdateFact(String machine, String container, String image) {
        static ContainerUpdateFact of(String machine, DockerService container) {
            return new ContainerUpdateFact(machine, container.containerName(), container.image());
        }
    }

    /** What one command printed on one machine. The command is echoed so a follow-up knows what was run. */
    record CommandFact(String machine, String command, int exitCode, boolean timedOut, String output, boolean cut) {
        static CommandFact of(Machine machine, String command, CommandOutcome outcome) {
            return new CommandFact(machine.name(), command, outcome.exitCode(), outcome.timedOut(),
                outcome.output(), outcome.cut());
        }
    }

    record BlockFact(String address, String why, String forHowLong, String country, String network) {
        static BlockFact of(BlockDecision decision) {
            return new BlockFact(decision.sourceIp(), decision.scenario(), decision.duration(),
                decision.country(), decision.asnOrg());
        }
    }
}
