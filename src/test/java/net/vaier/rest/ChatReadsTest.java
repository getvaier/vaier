package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.ChatTool;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.DockerService;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.Memory;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Reachability;
import net.vaier.domain.ReverseProxyRoute.ServiceLocation;
import net.vaier.domain.Server.State;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.WebPage;
import net.vaier.domain.WebSearchResult;
import net.vaier.domain.WebSearchResults;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every read Marvin may make, and the whole of what leaves Vaier for the Claude API (#360). Extracted from
 * {@code ChatRestControllerTest} when the <b>errand</b> arrived and a second caller — a run with nobody
 * watching — needed the same reads.
 *
 * <p>The test that matters most is {@link #noReadEverRendersASecret}: every projection, for a fixture that
 * carries a secret in every field that could hold one, read back to prove none of them comes out the other
 * side. The model never sees a key, and neither does Anthropic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatReadsTest {

    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock GetVpnPeersUseCase getVpnPeersUseCase;
    @Mock GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    @Mock ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock GetBackupJobsUseCase getBackupJobsUseCase;
    @Mock GetBackupRunsUseCase getBackupRunsUseCase;
    @Mock GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    @Mock DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    @Mock DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    @Mock GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    @Mock RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;
    @Mock SearchWebUseCase searchWebUseCase;
    @Mock ReadWebPageUseCase readWebPageUseCase;
    @Mock RememberUseCase rememberUseCase;
    @Mock ForgetUseCase forgetUseCase;

    private ChatReads chatReads() {
        return new ChatReads(getMachinesUseCase, getVpnPeersUseCase, getLanServerReachabilityUseCase,
            listEnrolmentRequestsUseCase, getPublishedServicesUseCase, getBackupJobsUseCase,
            getBackupRunsUseCase, getMachineDiskStandingsUseCase, discoverPeerContainersUseCase,
            discoverVaierServerContainersUseCase, getBlockDecisionsUseCase, runReadOnlyCommandUseCase,
            searchWebUseCase, readWebPageUseCase, rememberUseCase, forgetUseCase, new ObjectMapper());
    }

    private static final MachineId COLINA = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");
    private static final long NOW = 1_700_000_000_000L;

    /**
     * Exactly the reads Marvin may make alone, in the catalogue's own order — the domain's list, so an errand
     * is never offered a tool its own prompt does not describe.
     */
    @Test
    void itOffersExactlyTheReadsMarvinMayMakeAlone() {
        assertThat(chatReads().offers()).extracting(ToolOffer::tool)
            .containsExactlyElementsOf(ChatTool.whileNobodyIsWatching());
    }

    @Test
    void theFleetReadNamesEachMachineAndWhetherItIsReachable() {
        fleetOf();

        String fleet = read(ChatTool.FLEET);

        assertThat(fleet).contains("Colina 27").contains("10.13.13.3").contains("UBUNTU_SERVER");
        assertThat(fleet).contains("\"standing\":\"connected\"");
    }

    @Test
    void theFleetReadJudgesALanServerByItsLanProbe_andTheVaierServerAsAlwaysThere() {
        // The live answer called the NAS and the Vaier server "not connected" — a peer's word, for machines
        // that never had a tunnel. The machine's own verdict knows the difference.
        MachineId nas = MachineId.of("11111111-1111-1111-1111-111111111111");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(nas, "NAS", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
                null, "192.168.3.3", true, 2375, DeviceCategory.NAS, null),
            Machine.vaierServer(MachineId.of("22222222-2222-2222-2222-222222222222"), null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of());
        when(getLanServerReachabilityUseCase.getReachability("192.168.3.3")).thenReturn(Reachability.OK);

        String fleet = read(ChatTool.FLEET);

        assertThat(fleet).contains("\"name\":\"NAS\"").contains("\"standing\":\"reachable\"");
        assertThat(fleet).contains("\"standing\":\"this server, always reachable\"");
        assertThat(fleet).doesNotContain("not connected");
    }

    @Test
    void theFleetReadSaysNotCheckedYet_forALanServerVaierHasNotProbedSinceItStarted() {
        // For the first minutes after a restart the LAN probe has no verdict. A missing fact is not a bad one.
        MachineId nas = MachineId.of("11111111-1111-1111-1111-111111111111");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(nas, "NAS", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
                null, "192.168.3.3", true, 2375, DeviceCategory.NAS, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of());
        when(getLanServerReachabilityUseCase.getReachability("192.168.3.3")).thenReturn(Reachability.UNKNOWN);

        assertThat(read(ChatTool.FLEET)).contains("\"standing\":\"not checked yet\"").doesNotContain("unreachable");
    }

    /**
     * The tunnel address comes from the peer view, which the domain derived — it is not re-derived from
     * {@code allowedIps} here. "Which entry of an allowedIps list is the tunnel address" is a rule with a
     * relay-peer subtlety in it, and a second copy in a read is how Chat would come to tell the model an
     * address the peer pane disagrees with.
     */
    @Test
    void theFleetReadTakesTheTunnelAddressFromTheDomainsOwnReading() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET",
            "10.13.13.3/32, 192.168.1.0/24", "77.16.1.2", "51820", "1757000000", "1.2 GiB", "3.4 GiB",
            "192.168.1.0/24", "192.168.1.10", true, 2375, DeviceCategory.SERVER, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
            .id("colina27").machineId(COLINA.value()).name("Colina 27").tunnelIp("10.13.13.9")
            .peerType(MachineType.UBUNTU_SERVER).connected(true)
            .deviceCategory(DeviceCategory.SERVER).build()));

        assertThat(read(ChatTool.FLEET)).contains("10.13.13.9").doesNotContain("10.13.13.3/32");
    }

    @Test
    void theWaitingToJoinReadCarriesTheJoinCodeAndNeverTheTicketOrTheKey() {
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(
            new EnrolmentRequest("4417", "a-32-byte-unguessable-ticket", "Ruten",
                "aGVsbG8td29ybGQtdGhpcy1pcy1hLXdnLWtleS0xMjM0NQ=", System.currentTimeMillis() + 300_000,
                null)));

        String waiting = read(ChatTool.WAITING_TO_JOIN);

        assertThat(waiting).contains("4417").contains("Ruten");
        assertThat(waiting).doesNotContain("a-32-byte-unguessable-ticket");
        assertThat(waiting).doesNotContain("aGVsbG8td29ybGQ");
    }

    @Test
    void theBackupsReadSaysHowTheLastRunOfEachJobWent() {
        fleetOf();
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of(BackupJob.builder()
            .name("colina27-home").machineId(COLINA).repositoryName("colina27")
            .sourcePaths(List.of("/home")).excludes(List.of()).compression("zstd,6").enabled(true)
            .keepDaily(7).keepWeekly(4).keepMonthly(6).build()));
        when(getBackupRunsUseCase.latestForMachine(COLINA)).thenReturn(Optional.of(new BackupRun(
            "run-1", "colina27-home", "colina27", COLINA, BackupRunStatus.WARNING,
            Instant.parse("2026-09-08T02:00:00Z"), Instant.parse("2026-09-08T02:14:00Z"), 1,
            "colina27-{now}", "1 file vanished during the backup")));

        String backups = read(ChatTool.BACKUPS);

        assertThat(backups).contains("colina27-home").contains("WARNING").contains("Colina 27");
    }

    @Test
    void theDisksReadNamesTheFilesystemClosestToTrouble() {
        fleetOf();
        when(getMachineDiskStandingsUseCase.getMachineDiskStandings()).thenReturn(List.of(
            MachineDiskStanding.builder().machineId(COLINA).worstMountPoint("/volume1")
                .worstUsedPercent(86).worstThresholdPercent(85).breachingFilesystems(1)
                .watchedFilesystems(3).build()));

        String disks = read(ChatTool.DISKS);

        assertThat(disks).contains("/volume1").contains("86").contains("Colina 27");
    }

    @Test
    void theContainerUpdatesReadListsOnlyContainersWantingANewerImage() {
        fleetOf();
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(new PeerContainers(
            COLINA.value(), "colina27", "10.13.13.3", "OK", List.of(
                new DockerService("c1", "grafana", "grafana/grafana:11.3.0", "11.3.0", List.of(),
                    List.of(), "running", "sha256:aaa", UpdateAvailability.UPDATE_AVAILABLE),
                new DockerService("c2", "mosquitto", "eclipse-mosquitto:2.1.2", "2.1.2", List.of(),
                    List.of(), "running", "sha256:bbb", UpdateAvailability.UP_TO_DATE)),
            false, null)));

        String updates = read(ChatTool.CONTAINER_UPDATES);

        assertThat(updates).contains("grafana");
        assertThat(updates).doesNotContain("mosquitto");
    }

    @Test
    void theSecurityReadSaysWhoIsBeingKeptOut() {
        when(getBlockDecisionsUseCase.getBlockDecisions()).thenReturn(List.of(BlockDecision.builder()
            .id(11L).scenario("crowdsecurity/ssh-bf").sourceIp("203.0.113.7").type("ban")
            .duration("3h59m").country("RU").asnOrg("Example Telecom").build()));

        String security = read(ChatTool.SECURITY);

        assertThat(security).contains("203.0.113.7").contains("crowdsecurity/ssh-bf");
    }

    @Test
    void thePublishedServicesReadSaysWhereEachServiceRunsAndWhetherItIsReachable() {
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of(
            new PublishedServiceUco("Grafana @ Colina 27", "Grafana", COLINA.value(), "Colina 27", null,
                ServiceLocation.PEER_SERVER, true, "grafana.example.com", "10.13.13.3", 3000, State.OK, true,
                null, false, false, null, false, null, null, null, null, null, "social", false, null)));

        String services = read(ChatTool.PUBLISHED_SERVICES);

        assertThat(services).contains("Grafana").contains("Colina 27").contains("grafana.example.com");
    }

    /**
     * The one test that has to hold for the whole feature to be safe: every read Marvin may make, for a
     * fixture that carries a secret in every field that could hold one, and not one of them comes out the
     * other side.
     */
    @Test
    void noReadEverRendersASecret() {
        fleetOf();
        when(rememberUseCase.remember(any())).thenReturn(new Memory.Fact("ab12cd", "a fact", NOW));
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(
            new EnrolmentRequest("4417", "TICKET-SECRET", "Ruten", "PUBLICKEY-SECRET",
                System.currentTimeMillis() + 300_000, "CONFIGFILE-SECRET")));

        String everything = ChatTool.whileNobodyIsWatching().stream()
            .map(this::read)
            .collect(Collectors.joining("\n"));

        assertThat(everything)
            .doesNotContain("TICKET-SECRET", "PUBLICKEY-SECRET", "CONFIGFILE-SECRET", "PRESHARED-SECRET");
        assertThat(everything.toLowerCase()).doesNotContain(
            "publickey", "privatekey", "presharedkey", "passphrase", "password", "credential",
            "apikey", "ticket", "token", "secret", "configfile");
    }

    // --- run_on_machine: one looking command, on the machine the model named ----------------------------

    @Test
    void runOnMachine_findsTheMachineByName_andRunsThroughTheUseCase() {
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(COLINA, "apt list --upgradable"))
            .thenReturn(new CommandOutcome(0, false, "curl/noble-updates 8.5.0 amd64 [upgradable from: 8.4.0]", false));

        String fact = read(ChatTool.RUN_ON_MACHINE, Map.of("machine", "colina 27", "command", "apt list --upgradable"));

        assertThat(fact).contains("Colina 27").contains("apt list --upgradable").contains("curl/noble-updates")
            .contains("\"exitCode\":0");
    }

    /** The domain's refusal is the answer, in its own words, so the model can say so. */
    @Test
    void runOnMachine_saysWhyARefusedCommandWasRefused() {
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new IllegalArgumentException("Chat can look, never change: apt install is not a looking command."));

        assertThat(read(ChatTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "apt install vim")))
            .isEqualTo("Chat can look, never change: apt install is not a looking command.");
    }

    @Test
    void runOnMachine_saysWhenNoMachineHasThatName() {
        fleetOf();

        assertThat(read(ChatTool.RUN_ON_MACHINE, Map.of("machine", "Apalveien", "command", "uptime")))
            .contains("no machine called \"Apalveien\"");
        verifyNoInteractions(runReadOnlyCommandUseCase);
    }

    /** A machine Vaier holds no login for cannot be reached; said plainly, and never as a stack trace. */
    @Test
    void runOnMachine_saysWhenVaierHoldsNoCredentialForTheMachine() {
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new NoHostCredentialException("Colina 27"));

        assertThat(read(ChatTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "uptime")))
            .isEqualTo("No SSH credential is stored for Colina 27, so Vaier cannot run anything there.");
    }

    /**
     * A transport failure's own message can carry an address, a user or a path — the same reason the
     * emitter never repeats one — so it is answered in Vaier's words.
     */
    @Test
    void runOnMachine_neverRepeatsATransportFailuresOwnMessage() {
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new SshConnectException("connect to 10.13.13.3:22 as geir failed"));

        String fact = read(ChatTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "uptime"));

        assertThat(fact).isEqualTo("Colina 27 could not be reached over SSH.");
        assertThat(fact).doesNotContain("10.13.13.3").doesNotContain("geir");
    }

    // --- the web read: the public internet, and nothing else (#360) -------------------------------------

    @Test
    void searchWeb_readsThroughTheUseCase_andHandsTheModelTheNumberedResults() {
        when(searchWebUseCase.search("wireguard allowedips")).thenReturn(WebSearchResults.found(
            "wireguard allowedips", List.of(new WebSearchResult("WireGuard: Quick Start",
                "https://www.wireguard.com/quickstart/", "AllowedIPs is a routing table."))));

        String fact = read(ChatTool.SEARCH_WEB, Map.of("query", "wireguard allowedips"));

        assertThat(fact).isEqualTo("""
            1. WireGuard: Quick Start
               https://www.wireguard.com/quickstart/
               AllowedIPs is a routing table.""");
    }

    /** The domain's refusal is the answer, in its own words, so the model can say so and stop. */
    @Test
    void searchWeb_saysWhyARefusedQueryWasRefused() {
        when(searchWebUseCase.search(anyString()))
            .thenThrow(new IllegalArgumentException("Say what to search for."));

        assertThat(read(ChatTool.SEARCH_WEB, Map.of("query", " ")))
            .isEqualTo("Say what to search for.");
    }

    /** An unexpected failure's own message can carry a proxy or a certificate, so it is said in Vaier's words. */
    @Test
    void searchWeb_neverRepeatsAnUnexpectedFailuresOwnMessage() {
        when(searchWebUseCase.search(anyString()))
            .thenThrow(new IllegalStateException("TLS handshake to 52.29.74.114:443 failed"));

        String fact = read(ChatTool.SEARCH_WEB, Map.of("query", "anything"));

        assertThat(fact).isEqualTo("Vaier could not search just now.");
        assertThat(fact).doesNotContain("52.29.74.114");
    }

    @Test
    void readWebPage_readsThroughTheUseCase_andHandsTheModelThePage() {
        when(readWebPageUseCase.read("https://www.wireguard.com/quickstart/")).thenReturn(
            WebPage.fromHtml("https://www.wireguard.com/quickstart/",
                "<title>Quick start</title><p>AllowedIPs is a routing table.</p>"));

        String fact = read(ChatTool.READ_WEB_PAGE, Map.of("url", "https://www.wireguard.com/quickstart/"));

        assertThat(fact).isEqualTo("""
            Title: Quick start
            Address: https://www.wireguard.com/quickstart/

            Quick start AllowedIPs is a routing table.""");
    }

    /** The one refusal that matters, worded by the domain and repeated here without dressing it up. */
    @Test
    void readWebPage_saysWhyAnAddressOffThePublicInternetWasRefused() {
        when(readWebPageUseCase.read(anyString())).thenThrow(new IllegalArgumentException(
            "Marvin reads only the public internet; 169.254.169.254 is not on it."));

        assertThat(read(ChatTool.READ_WEB_PAGE, Map.of("url", "http://169.254.169.254/latest/meta-data/")))
            .isEqualTo("Marvin reads only the public internet; 169.254.169.254 is not on it.");
    }

    @Test
    void readWebPage_neverRepeatsAnUnexpectedFailuresOwnMessage() {
        when(readWebPageUseCase.read(anyString()))
            .thenThrow(new IllegalStateException("read timed out from 10.13.13.6"));

        String fact = read(ChatTool.READ_WEB_PAGE, Map.of("url", "https://example.com/"));

        assertThat(fact).isEqualTo("Vaier could not read that.");
        assertThat(fact).doesNotContain("10.13.13.6");
    }

    // --- memory: what Vaier keeps across conversations (#360) --------------------------------------------

    @Test
    void remembering_keepsTheFact_andTellsTheModelSo() {
        when(rememberUseCase.remember("Photos live under /volume1/photo.")).thenReturn(
            new Memory.Fact("ab12cd", "Photos live under /volume1/photo.", NOW));

        assertThat(read(ChatTool.REMEMBER, Map.of("fact", "Photos live under /volume1/photo.")))
            .isEqualTo("Remembered [ab12cd]: Photos live under /volume1/photo.");
    }

    @Test
    void remembering_nothing_isRefusedInTheDomainsWords() {
        when(rememberUseCase.remember(any())).thenThrow(new IllegalArgumentException("Say what to remember."));

        assertThat(read(ChatTool.REMEMBER, Map.of("fact", " "))).isEqualTo("Say what to remember.");
    }

    @Test
    void forgetting_dropsTheFact_orSaysItWasNeverThere() {
        assertThat(read(ChatTool.FORGET, Map.of("id", "ab12cd"))).isEqualTo("Forgotten.");
        verify(forgetUseCase).forget("ab12cd");

        doThrow(new NotFoundException("Vaier has no memory with the id nope.")).when(forgetUseCase).forget("nope");
        assertThat(read(ChatTool.FORGET, Map.of("id", "nope"))).isEqualTo("Vaier has no memory with the id nope.");
    }

    // --- fixtures and plumbing -------------------------------------------------------------------------

    /** A one-machine fleet, connected, so every machine-keyed projection has a name to use. */
    private void fleetOf() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET", "10.13.13.3/32",
            "77.16.1.2", "51820", String.valueOf(System.currentTimeMillis() / 1000 - 30), "1.2 GiB", "3.4 GiB",
            "192.168.1.0/24", "192.168.1.10",
            true, 2375, DeviceCategory.SERVER, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
            .id("colina27").machineId(COLINA.value()).name("Colina 27").publicKey("PUBLICKEY-SECRET")
            .tunnelIp("10.13.13.3").peerType(MachineType.UBUNTU_SERVER).connected(true)
            .lanCidr("192.168.1.0/24").description("the relay in the garage")
            .deviceCategory(DeviceCategory.SERVER).build()));
    }

    /** What the named read answers with, given what the model said. */
    private String read(ChatTool tool) {
        return read(tool, Map.of());
    }

    private String read(ChatTool tool, Map<String, String> arguments) {
        return chatReads().offers().stream()
            .filter(offer -> offer.tool() == tool)
            .findFirst().orElseThrow()
            .read().apply(arguments);
    }
}
