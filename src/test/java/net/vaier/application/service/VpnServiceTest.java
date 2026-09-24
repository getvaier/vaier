package net.vaier.application.service;

import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.application.GetServerLocationUseCase.ServerLocation;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecordType;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.LanServer;
import net.vaier.domain.LastServiceReached;
import net.vaier.domain.LastServicesReached;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSetupScript;
import net.vaier.domain.DeviceClaim;
import net.vaier.domain.MachinePosition;
import net.vaier.domain.MachinePositions;
import net.vaier.domain.PlacementSource;
import net.vaier.domain.PositionTrail;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.UnidentifiedDeviceException;
import net.vaier.domain.VpnClient;
import net.vaier.domain.WireGuardPeerConfig;
import net.vaier.domain.port.ForPersistingLastServicesReached;
import net.vaier.domain.port.ForPersistingMachinePositions;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingServerPublicKey;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForHoldingEnrolmentRequests;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerIds;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import net.vaier.domain.port.ForSyncingLanRoutes;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForUpdatingServerAllowedIps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnServiceTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Mock ConfigResolver configResolver;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForGettingServerPublicKey forGettingServerPublicKey;
    @Mock ForResolvingPeerIds forResolvingPeerIds;
    @Mock ForGettingPeerConfigurations peerConfigProvider;
    @Mock ForDeletingVpnPeers vpnPeerDeleter;
    @Mock ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    @Mock ForGeneratingDockerComposeFiles dockerComposeGenerator;
    @Mock DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    @Mock ForResolvingPublicHost forResolvingPublicHost;
    @Mock ForGeolocatingIps forGeolocatingIps;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock ForUpdatingServerAllowedIps forUpdatingServerAllowedIps;
    @Mock ForSyncingLanRoutes forSyncingLanRoutes;
    @Mock ForExecutingInContainer forExecutingInContainer;
    @Mock ForResolvingServerLanCidr forResolvingServerLanCidr;
    @Mock net.vaier.domain.port.ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock net.vaier.domain.port.ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock net.vaier.domain.port.ForTrackingHostKeys forTrackingHostKeys;
    @Mock ForPersistingMachinePositions forPersistingMachinePositions;
    @Mock ForPersistingLastServicesReached forPersistingLastServicesReached;
    @Mock ForHoldingEnrolmentRequests forHoldingEnrolmentRequests;

    @InjectMocks VpnService service;

    /** @Value-injected in production, so a unit fixture without it has nothing on the tunnel at all. */
    @BeforeEach
    void theDefaultVpnSubnet() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
    }

    /** No device has reported or been claimed unless a test says so — the ordinary fleet-wide state. */
    @BeforeEach
    void noMachinePositionsByDefault() {
        lenient().when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.empty());
        lenient().when(forPersistingLastServicesReached.getAll())
            .thenReturn(LastServicesReached.empty());
    }

    // --- getClients ---

    @Test
    void getClients_delegatesToPort() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        assertThat(service.getClients()).containsExactly(client);
    }

    @Test
    void getClients_returnsEmptyListWhenNoClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.getClients()).isEmpty();
    }

    // --- resolvePeerIdByIp ---

    @Test
    void resolvePeerIdByIp_delegatesToPort() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");

        assertThat(service.resolvePeerIdByIp("10.13.13.2")).isEqualTo("alice");
    }

    @Test
    void resolvePeerIdByIp_returnsNullWhenNotFound() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn(null);

        assertThat(service.resolvePeerIdByIp("10.13.13.99")).isNull();
    }

    // --- getPeerConfig ---

    @Test
    void getPeerConfig_byName_callsGetByName() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("alice");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.2");
        verify(peerConfigProvider).getPeerConfigByName("alice");
    }

    @Test
    void getPeerConfig_byIp_callsGetByIp() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("10.13.13.2");

        assertThat(result).isPresent();
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getPeerConfig_notFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("nobody")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfig("nobody")).isEmpty();
    }

    @Test
    void getPeerConfig_mapsPeerConfigurationFieldsCorrectly() {
        when(peerConfigProvider.getPeerConfigByName("bob")).thenReturn(
            Optional.of(new PeerConfiguration("bob", "10.13.13.3", "wg-config-content"))
        );

        PeerConfigResult result = service.getPeerConfig("bob").orElseThrow();

        assertThat(result.name()).isEqualTo("bob");
        assertThat(result.ipAddress()).isEqualTo("10.13.13.3");
        assertThat(result.configContent()).isEqualTo("wg-config-content");
    }

    @Test
    void getPeerConfigByIp_delegatesToPort() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfigByIp("10.13.13.2");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getPeerConfigByIp_returnsEmptyWhenNotFound() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.99")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfigByIp("10.13.13.99")).isEmpty();
    }

    // --- generateWireguardClientDockerCompose ---

    @Test
    void generateWireguardClientDockerCompose_passesCorrectConfigToPort() {
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(
            new DockerComposeConfig("alice", "vpn.example.com", "51820")
        )).thenReturn("docker-compose-yaml-content");

        String result = service.generateWireguardClientDockerCompose("alice", "vpn.example.com", "51820");

        assertThat(result).isEqualTo("docker-compose-yaml-content");
    }

    // --- generateSetupScript ---

    @Test
    void generateSetupScript_peerNotFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("unknown")).thenReturn(Optional.empty());

        assertThat(service.generateSetupScript("unknown", "vpn.example.com", "51820")).isEmpty();
    }

    @Test
    void generateSetupScript_rendersThePeersScript_withNameIpServerAndDockerBootstrap() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<String> result = service.generateSetupScript("alice", "vpn.example.com", "51820");

        assertThat(result).isPresent();
        String script = result.orElseThrow();
        assertThat(script).isNotBlank();
        assertThat(script).startsWith("#!/bin/bash");
        assertThat(script).contains("alice");
        assertThat(script).contains("10.13.13.2");
        assertThat(script).contains("vpn.example.com");
        assertThat(script).contains("51820");
        assertThat(script).contains("systemctl enable docker");
        assertThat(script).contains("systemctl enable docker || true");
        assertThat(script).contains("systemctl restart docker");
        assertThat(script).contains("systemctl restart docker || sudo service docker restart || true");
        assertThat(script).contains("snap.docker.dockerd");
        assertThat(script).contains("/var/snap/docker/current/config/daemon.json");
    }

    @Test
    void generateSetupScript_usesConfiguredVpnSubnetInFirewallRules() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.10.10.0/24");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.10.10.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("10.10.10.0/24");
        assertThat(script).doesNotContain("10.13.13.0/24");
    }

    // --- generateSetupScript: a relay's forwarding follows its stored config (#170, #191, #250) ---

    @Test
    void generateSetupScript_handsTheRelaysStoredConfigAndLanToTheDomainScript() {
        String stored = "[Peer]\nAllowedIPs = 10.13.13.0/24,172.31.16.0/20,192.168.3.0/24\n";
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", stored,
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        assertThat(service.generateSetupScript("homelab", "vpn.example.com", "51820")).contains(
            PeerSetupScript.generate("homelab", "10.13.13.5", "vpn.example.com", "51820", stored,
                "192.168.1.0/24", "10.13.13.0/24"));
    }

    // --- generateSetupScript: wireguard image pinning (drift guard, #175) ---

    @Test
    void generateSetupScript_pinsWireguardImageNotLatest() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script)
            .contains("image: " + net.vaier.domain.WireguardClientImage.EXPECTED)
            .doesNotContain("wireguard:latest");
    }

    @Test
    void generateSetupScript_pinsWireguardImageToSameVersionAsServer() throws Exception {
        // Drift guard: install-script wireguard image must match the server's docker-compose.yml pin.
        String serverCompose = Files.readString(Path.of("docker-compose.yml"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "image:\\s*(lscr\\.io/linuxserver/wireguard:\\S+)").matcher(serverCompose);
        assertThat(m.find()).as("server docker-compose.yml should declare a wireguard image").isTrue();
        String serverImage = m.group(1);
        assertThat(serverImage).as("server wireguard must be pinned, not :latest").doesNotEndWith(":latest");

        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("image: " + serverImage);
    }

    // --- generateSetupScript: rerun cleanup for orphaned wg0 ---

    @Test
    void generateSetupScript_deletesOrphanedWg0InterfaceBeforeStartingContainer() {
        // Re-running the install script must clean up a leftover host-netns wg0 interface
        // (linuxserver/wireguard runs network_mode: host and doesn't run wg-quick down on
        // container shutdown). Without this cleanup the new container fails with
        // "wg-quick: wg0 already exists" and the tunnel is left orphaned with no driver.
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("ip link delete wg0 2>/dev/null || true");

        int downIdx       = script.indexOf("docker compose down");
        int linkDeleteIdx = script.indexOf("ip link delete wg0");
        int composeUpIdx  = script.lastIndexOf("docker_compose_up");
        assertThat(downIdx).as("docker compose down should appear in script").isGreaterThanOrEqualTo(0);
        assertThat(composeUpIdx).as("docker_compose_up should appear in script").isGreaterThanOrEqualTo(0);
        assertThat(linkDeleteIdx)
            .as("wg0 cleanup must run after docker compose down and before docker_compose_up")
            .isBetween(downIdx, composeUpIdx);
    }

    // --- deletePeer ---

    @Test
    void deletePeer_byName_deletesDirectlyWithoutResolving() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(forResolvingPeerIds);
    }

    @Test
    void deletePeer_byIp_resolvesToNameBeforeDeleting() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("10.13.13.2");

        verify(forResolvingPeerIds).resolvePeerIdByIp("10.13.13.2");
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_ipNotResolved_throwsPeerNotFound() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(PeerNotFoundException.class)
            .hasMessageContaining("10.13.13.99");
    }

    @Test
    void deletePeer_ipNotResolved_doesNotCallDeleter() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(PeerNotFoundException.class);

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void deletePeer_ipLikeStringWithOutOfRangeOctets_isTreatedAsAPeerName() {
        // "999.999.999.999" is not a valid IPv4 literal, so it is taken as a peer name
        // directly — no IP-to-name resolution is attempted.
        service.deletePeer("999.999.999.999");

        verify(vpnPeerDeleter).deletePeer("999.999.999.999");
    }

    @Test
    void deletePeer_deletesPublishedServicesPointingToPeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        ReverseProxyRoute otherRoute = new ReverseProxyRoute("other-router", "other.example.com", "10.13.13.3", 9090, "other-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute, otherRoute));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        verify(deletePublishedServiceUseCase, never()).deleteService(eq("other.example.com"), any());
    }

    @Test
    void deletePeer_deletesMultipleServicesPointingToSamePeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute route1 = new ReverseProxyRoute("app1-router", "app1.example.com", "10.13.13.2", 8080, "app1-service", null);
        ReverseProxyRoute route2 = new ReverseProxyRoute("app2-router", "app2.example.com", "10.13.13.2", 9090, "app2-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route1, route2));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app1.example.com", null);
        verify(deletePublishedServiceUseCase).deleteService("app2.example.com", null);
    }

    @Test
    void deletePeer_doesNotCascadeIntoApiOnlyDockerRouteOnSamePeerIp() {
        // An API-only Traefik route (name@provider) has no file entry; deleting it would throw
        // "Router not found" and abort the peer deletion. The cascade must skip it even when it
        // shares the peer's IP — only Vaier-managed file routes cascade.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));
        ReverseProxyRoute dockerRoute = new ReverseProxyRoute("app@docker", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(dockerRoute));

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verify(deletePublishedServiceUseCase, never()).deleteService(any(), any());
    }

    @Test
    void deletePeer_noPublishedServicesForPeer_stillDeletesPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_peerConfigNotFound_stillDeletesPeerWithoutCleaningServices() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_byIp_usesResolvedIpForServiceCleanup() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("10.13.13.2");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_deletesServicesBeforeDeletingPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("alice");

        var order = inOrder(deletePublishedServiceUseCase, vpnPeerDeleter);
        order.verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        order.verify(vpnPeerDeleter).deletePeer("alice");
    }

    // --- getServerLocation ---

    @Test
    void getServerLocation_prefersResolvedPublicIpForGeolocation() {
        // On EC2 the public hostname resolves to a private VPC IP, so the service should ask the
        // port for the direct public IP rather than DNS-resolving the CNAME.
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-54-93-32-13.eu-central-1.compute.amazonaws.com", DnsRecordType.CNAME)));
        when(forResolvingPublicHost.resolvePublicIp()).thenReturn(Optional.of("54.93.32.13"));
        when(forGeolocatingIps.locate("54.93.32.13"))
            .thenReturn(Optional.of(new GeoLocation(50.11, 8.68, "Frankfurt", "Germany")));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        // Display label keeps the friendly hostname, geolocation uses the public IP.
        assertThat(result.get().publicHost()).isEqualTo("ec2-54-93-32-13.eu-central-1.compute.amazonaws.com");
        assertThat(result.get().latitude()).isEqualTo(50.11);
        assertThat(result.get().longitude()).isEqualTo(8.68);
        assertThat(result.get().city()).isEqualTo("Frankfurt");
        assertThat(result.get().country()).isEqualTo("Germany");
    }

    @Test
    void getServerLocation_geolocatesARecordValueDirectly() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isEqualTo("203.0.113.10");
        assertThat(result.get().latitude()).isEqualTo(59.91);
        assertThat(result.get().longitude()).isEqualTo(10.74);
        assertThat(result.get().city()).isEqualTo("Oslo");
        assertThat(result.get().country()).isEqualTo("Norway");
    }

    @Test
    void getServerLocation_resolvesCnameToIpThenGeolocates() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("localhost", DnsRecordType.CNAME)));
        when(forGeolocatingIps.locate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new GeoLocation(0.0, 0.0, null, null)));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isEqualTo("localhost");
    }

    @Test
    void getServerLocation_fallsBackToVaierDomainWhenNoPublicHostConfigured() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        // The DNS resolution of vaier.eilertsen.family at test time is unpredictable, so we just verify
        // the geolocation port is consulted (with whatever IP came back) and the fallback hostname is used.
        when(forGeolocatingIps.locate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        Optional<ServerLocation> result = service.getServerLocation();

        // If DNS resolves the test hostname (e.g. real internet), we get the fallback path.
        // If not, result is empty — both are valid in the test environment, so we only assert on
        // the publicHost label when the result is present.
        if (result.isPresent()) {
            assertThat(result.get().publicHost()).isEqualTo("vaier.eilertsen.family");
        }
    }

    @Test
    void getServerLocation_returnsEmptyWhenNothingConfigured() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);

        assertThat(service.getServerLocation()).isEmpty();
        verifyNoInteractions(forGeolocatingIps);
    }

    @Test
    void getServerLocation_populatesLanCidrFromResolverWhenGeoSucceeds() {
        // The LAN CIDR rides along on the same response as geolocation so the dashboard fetches
        // both server-only facts in one call (#204 surface on the Vaier-server machine card).
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().lanCidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void getServerLocation_returnsLanCidrEvenWhenGeolocationUnavailable() {
        // Geoip-init may not have populated the MMDB yet, or the public host can't be resolved.
        // The LAN CIDR is independent and useful on its own — surface it without blocking on geo.
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isNull();
        assertThat(result.get().latitude()).isNull();
        assertThat(result.get().longitude()).isNull();
        assertThat(result.get().lanCidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void getServerLocation_returnsEmptyWhenNoGeoAndNoLanCidr() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());

        assertThat(service.getServerLocation()).isEmpty();
    }

    @Test
    void getServerLocation_returnsEmptyWhenCnameDoesNotResolveAndNoDomain() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("does-not-resolve.invalid", DnsRecordType.CNAME)));
        when(configResolver.getDomain()).thenReturn("");

        assertThat(service.getServerLocation()).isEmpty();
    }

    // --- syncLanRoutes ---

    @Test
    void syncLanRoutes_passesEveryRelayCidr_toLanRouteAdapter() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, "192.168.3.0/24", null),
            new PeerConfiguration("alice",      "10.13.13.2", "config", MachineType.WINDOWS_CLIENT,    null,            null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", MachineType.UBUNTU_SERVER, "192.168.4.0/24", null)
        ));

        service.syncLanRoutes();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Set<String>> captor = ArgumentCaptor.forClass(java.util.Set.class);
        verify(forSyncingLanRoutes).syncLanRoutes(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("192.168.3.0/24", "192.168.4.0/24");
    }

    @Test
    void syncLanRoutes_skipsBlankAndNullCidrs() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice", "10.13.13.2", "config", MachineType.WINDOWS_CLIENT,    null, null),
            new PeerConfiguration("blank", "10.13.13.3", "config", MachineType.UBUNTU_SERVER, "  ", null)
        ));

        service.syncLanRoutes();

        verify(forSyncingLanRoutes).syncLanRoutes(java.util.Set.of());
    }

    @Test
    void updateLanCidr_alsoSyncsLanRoutes() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null)));

        service.updateLanCidr("apalveien5", "192.168.3.0/24");

        // Sync must run AFTER the persistence step — otherwise the sync would read the pre-change CIDRs.
        var order = inOrder(forUpdatingPeerConfigurations, forSyncingLanRoutes);
        order.verify(forUpdatingPeerConfigurations).updateLanCidr("apalveien5", "192.168.3.0/24");
        order.verify(forSyncingLanRoutes).syncLanRoutes(any());
    }

    // --- updateLanCidr (#176) ---

    @Test
    void updateLanCidr_setsServerSideAllowedIpsAndMetadata() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null)));

        service.updateLanCidr("apalveien5", "192.168.3.0/24");

        var order = inOrder(forUpdatingServerAllowedIps, forUpdatingPeerConfigurations);
        order.verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.6", "10.13.13.6/32,192.168.3.0/24");
        order.verify(forUpdatingPeerConfigurations).updateLanCidr("apalveien5", "192.168.3.0/24");
    }

    @Test
    void updateLanCidr_clearingStripsServerSideAllowedIps() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", null);

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
        verify(forUpdatingPeerConfigurations).updateLanCidr("nuc02", null);
    }

    @Test
    void updateLanCidr_blankIsTreatedAsClear() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", "  ");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
    }

    @Test
    void updateLanCidr_changingReplacesServerSideCidr() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.5.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.5.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.5.0/24");
    }

    @Test
    void updateLanCidr_rejectsConflictWhenAnotherPeerOwnsTheCidr() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        assertThatThrownBy(() -> service.updateLanCidr("apalveien5", "192.168.3.0/24"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("nuc02")
            .hasMessageContaining("192.168.3.0/24");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    @Test
    void updateLanCidr_allowsSameCidrOnSamePeerIdempotent() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.1.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.1.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.1.0/24");
    }

    @Test
    void updateLanCidr_throwsWhenPeerDoesNotExist() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLanCidr("ghost", "192.168.3.0/24"))
            .isInstanceOf(PeerNotFoundException.class)
            .hasMessageContaining("ghost");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    // --- updateLanCidr (#195) — reject shell-injection payloads at the boundary ---

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "1.2.3.0/24; id",
        "1.2.3.0/24 | id",
        "1.2.3.0/24`id`",
        "1.2.3.0/24$(id)",
        "1.2.3.0/24\nid",
        "256.0.0.0/24",
        "1.2.3.4/33",
        "not-a-cidr"
    })
    void updateLanCidr_rejectsCommandInjectionAndMalformedCidr(String malicious) {
        // The injection check must fire BEFORE any peer lookup or persistence call.
        // Otherwise an attacker could probe peer existence + leave audit traces
        // even on rejected requests.
        assertThatThrownBy(() -> service.updateLanCidr("apalveien5", malicious))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");

        verifyNoInteractions(peerConfigProvider, forUpdatingServerAllowedIps, forUpdatingPeerConfigurations, forSyncingLanRoutes);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "1.2.3.0/24; id",
        "256.0.0.0/24",
        "1.2.3.4/33"
    })
    void createPeer_rejectsCommandInjectionAndMalformedCidr(String malicious) {
        assertThatThrownBy(() -> service.createPeer("evilpeer", MachineType.UBUNTU_SERVER, malicious))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");

        verifyNoInteractions(peerConfigProvider, forUpdatingServerAllowedIps,
            forUpdatingPeerConfigurations, forGettingVpnClients);
    }

    // --- createPeer / renamePeer: a machine name is a label, not a key (§6.22) ---

    @Test
    void createPeer_allowsANameAnotherPeerAlreadyWears() {
        // The payoff. Names had to be unique because records hung off them; everything hangs off a
        // MachineId now. The peer ID still deduplicates — it is a directory on disk — but that is a
        // filesystem constraint, not an opinion about what an operator may call their machines.
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nas", "10.13.13.2", "config")
        ));

        // It no longer stops at the name — it reaches key generation. (It fails after that for want of a
        // real WireGuard container, which is this unit test's harness, not a rule about names.)
        assertThatThrownBy(() -> service.createPeer("nas")).isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void createPeer_allowsALanServersName() {
        // The LAN-server list is not even read any more: there is no name to be free of. That the stub
        // would be unnecessary here is itself the point.
        assertThatThrownBy(() -> service.createPeer("nas")).isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void createPeer_allowsTheVaierServersOwnName() {
        // "Vaier server" was reserved because the Vaier server was recognised BY that name. It is
        // recognised by its identity now, so the string is just a string.
        assertThatThrownBy(() -> service.createPeer(net.vaier.domain.LanAnchor.VAIER_SERVER_NAME))
            .isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void renamePeer_allowsANameAnotherMachineAlreadyWears() {
        when(peerConfigProvider.getPeerConfigByName("laptp"))
            .thenReturn(Optional.of(new PeerConfiguration("laptp", "10.13.13.2", "config")));

        // Another machine is already called "nas". Nothing is keyed to a name, so nothing objects.
        service.renamePeer("laptp", "nas");

        verify(forUpdatingPeerConfigurations).updateName("laptp", "nas");
    }

    // --- renamePeer migrates name-keyed SSH state (#312) ---

    @Test
    void renamePeer_migratesSshCredentialAndHostKeyPinToNewName() {
        // Renaming sets a label. The credential and host-key pin hang off the peer's identity, which a
        // rename does not touch — so there is nothing to carry, and carrying nothing is the point.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        service.renamePeer("alice", "wonderland");

        verify(forUpdatingPeerConfigurations).updateName("alice", "wonderland");
        verify(forPersistingHostCredentials, never()).save(any());
        verify(forPersistingHostCredentials, never()).deleteByMachine(any());
        verify(forTrackingHostKeys, never()).pin(any(), any());
        verify(forTrackingHostKeys, never()).clear(any());
    }


    @Test
    void renamePeer_noOpSameName_leavesSshStateIntact() {
        // "alice" (peerId) already displays as "alice"; renaming to the same effective label is a no-op.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        service.renamePeer("alice", "alice");

        verify(forPersistingHostCredentials, never()).deleteByMachine(any());
        verify(forTrackingHostKeys, never()).clear(any());
    }

    @Test
    void renamePeer_clearingName_allowedWhenHumanisedIdFallbackIsFree() {
        when(peerConfigProvider.getPeerConfigByName("media-server"))
            .thenReturn(Optional.of(new PeerConfiguration("media-server", "10.13.13.2", "config")));

        service.renamePeer("media-server", "");

        verify(forUpdatingPeerConfigurations).updateName("media-server", "");
    }

    // --- updatePeerDeviceCategory: orthogonal icon override ---

    @Test
    void updatePeerDeviceCategory_blankClearsOverride() {
        when(peerConfigProvider.getPeerConfigByName("nas"))
            .thenReturn(Optional.of(new PeerConfiguration("nas", "10.13.13.2", "config")));

        service.updatePeerDeviceCategory("nas", "  ");

        // Blank normalises to null ("clear the override") — the service never forwards the raw,
        // unparsed request string to the port.
        verify(forUpdatingPeerConfigurations).updateDeviceCategory("nas", null);
    }

    @Test
    void updatePeerDeviceCategory_persistsNormalisedEnumNameNotRawCasing() {
        when(peerConfigProvider.getPeerConfigByName("nas"))
            .thenReturn(Optional.of(new PeerConfiguration("nas", "10.13.13.2", "config")));

        service.updatePeerDeviceCategory("nas", "nas");

        // The parsed enum name is persisted, not the raw lower-case request value.
        verify(forUpdatingPeerConfigurations).updateDeviceCategory("nas", "NAS");
    }

    @Test
    void updatePeerDeviceCategory_rejectsInvalidValueWithoutPersisting() {
        assertThatThrownBy(() -> service.updatePeerDeviceCategory("nas", "BANANA"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forUpdatingPeerConfigurations, never()).updateDeviceCategory(any(), any());
    }

    @Test
    void updatePeerDeviceCategory_throwsWhenPeerMissing() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePeerDeviceCategory("ghost", "NAS"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).updateDeviceCategory(any(), any());
    }

    // --- renamePeer: sets the display name; the id is immutable (#209, #55) ---

    @Test
    void renamePeer_setsDisplayNameViaUpdatePort() {
        when(peerConfigProvider.getPeerConfigByName("laptp"))
            .thenReturn(Optional.of(new PeerConfiguration("laptp", "10.13.13.2", "config")));

        service.renamePeer("laptp", "My Laptop");

        verify(forUpdatingPeerConfigurations).updateName("laptp", "My Laptop");
    }

    @Test
    void renamePeer_keepsTypedNameVerbatim_neitherSanitisingNorMovingFiles() {
        // The id (config directory name) is frozen; the display name is free text stored exactly
        // as typed — spaces, punctuation and case all preserved, no slugging (issue #209).
        when(peerConfigProvider.getPeerConfigByName("media-server"))
            .thenReturn(Optional.of(new PeerConfiguration("media-server", "10.13.13.2", "config")));

        service.renamePeer("media-server", "Media Server #1");

        verify(forUpdatingPeerConfigurations).updateName("media-server", "Media Server #1");
    }

    @Test
    void renamePeer_throwsWhenPeerNotFound() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renamePeer("ghost", "Phantom"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).updateName(any(), any());
    }

    // --- getVpnPeers (#220) ---

    @Test
    void getVpnPeers_returnsEmptyWhenNoClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.getVpnPeers()).isEmpty();
    }

    @Test
    void getVpnPeers_aConfiguredPeerTheInterfaceHasForgotten_isStillListed_disconnected() {
        // Two "Ruten" machines on the fleet page, one undeletable: its directory outlived its wg0 entry,
        // so it was in /machines but not in the peers list, and the page took it for a LAN server.
        when(forGettingVpnClients.getClients()).thenReturn(List.of());
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("Ruten", "10.13.13.8", "[Interface]", MachineType.MOBILE_CLIENT, null, null)));

        var views = service.getVpnPeers();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo("Ruten");
        assertThat(views.get(0).tunnelIp()).isEqualTo("10.13.13.8");
        assertThat(views.get(0).connected()).isFalse();
    }

    @Test
    void getVpnPeers_assemblesFromClientPlusPeerConfigPlusGeo() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10", "alice's box")));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        var view = service.getVpnPeers().get(0);

        assertThat(view.id()).isEqualTo("alice-1");
        assertThat(view.name()).isEqualTo("Alice");
        assertThat(view.publicKey()).isEqualTo("pub");
        assertThat(view.peerType()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(view.tunnelIp()).isEqualTo("10.13.13.2");
        assertThat(view.isServer()).isTrue();
        assertThat(view.isClient()).isFalse();
        assertThat(view.isRelay()).isTrue();
        assertThat(view.availableArtifacts()).contains(
            net.vaier.domain.PeerArtifact.WG_CONFIG,
            net.vaier.domain.PeerArtifact.DOCKER_COMPOSE,
            net.vaier.domain.PeerArtifact.SETUP_SCRIPT);
        assertThat(view.lanCidr()).isEqualTo("192.168.1.0/24");
        assertThat(view.lanAddress()).isEqualTo("192.168.1.10");
        assertThat(view.description()).isEqualTo("alice's box");
        assertThat(view.geoLocation()).contains(new GeoLocation(59.91, 10.74, "Oslo", "Norway"));
    }

    @Test
    void getVpnPeers_readsThePeerConfigsOnceForTheWholeList_neverPerPeer() {
        // The roster already reads every stored config once; a peer then cost two directory scans more —
        // one to resolve its name, one to load the same config again — 51 debug lines per request on a
        // fleet of a handful. Both come from the one read now.
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10", "alice's box")));

        var view = service.getVpnPeers().get(0);

        assertThat(view.id()).isEqualTo("alice-1");
        assertThat(view.name()).isEqualTo("Alice");
        verify(peerConfigProvider, times(1)).getAllPeerConfigs();
        verify(peerConfigProvider, never()).getPeerConfigByIp(any());
        verify(forResolvingPeerIds, never()).resolvePeerIdByIp(any());
    }

    @Test
    void getVpnPeers_mobileClient_isClientNotServer_andOffersQrCode() {
        VpnClient client = new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.5", "",
                MachineType.MOBILE_CLIENT, null, null, null)));

        var view = service.getVpnPeers().get(0);

        assertThat(view.isServer()).isFalse();
        assertThat(view.isClient()).isTrue();
        assertThat(view.isRelay()).isFalse();
        assertThat(view.availableArtifacts())
            .containsExactlyInAnyOrder(
                net.vaier.domain.PeerArtifact.WG_CONFIG,
                net.vaier.domain.PeerArtifact.QR_CODE);
    }

    @Test
    void getVpnPeers_anEnrolledPhone_offersNothingToDownload() {
        // Its private key was minted on the phone. There is no config to hand out and no QR to
        // photograph, so the machine pane must offer neither — the decision is PeerArtifact's.
        VpnClient client = new VpnClient("pub", "10.13.13.7/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.7", "", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, mid("phone"), DEVICE_KEY)));

        assertThat(service.getVpnPeers().get(0).availableArtifacts()).isEmpty();
    }

    @Test
    void getVpnPeers_anEnrolledPhone_saysSoInItsOwnRight() {
        // The pane needs the fact itself, not only its consequence for downloads: it is what decides
        // whether Reissue and Regenerate are offered at all.
        VpnClient client = new VpnClient("pub", "10.13.13.7/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("ruten", "Ruten", "10.13.13.7", "", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, mid("ruten"), DEVICE_KEY)));

        assertThat(service.getVpnPeers().get(0).deviceHeldKey()).isTrue();
    }

    @Test
    void getVpnPeers_anOrdinaryPeer_holdsNoDeviceHeldKey() {
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", "[Interface]",
                MachineType.UBUNTU_SERVER, null, null, null)));

        assertThat(service.getVpnPeers().get(0).deviceHeldKey()).isFalse();
    }

    @Test
    void getVpnPeers_anEnrolledPhoneWhoseServerMoved_isNeverMarkedOutOfDate() {
        // Reissue is the only action behind that mark, and it is refused for a device-held key.
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "OLD_PUB", "PSK", "old.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Ruten", null, null,
            mid("ruten"), DEVICE_KEY);
        VpnClient client = new VpnClient("pub", "10.13.13.7/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("ruten", "Ruten", "10.13.13.7", existing, MachineType.MOBILE_CLIENT,
                null, null, null, null, null, mid("ruten"), DEVICE_KEY)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isFalse();
    }

    @Test
    void getVpnPeers_fallsBackToDefaultTypeAndDisplayLabelWhenNoPeerConfig() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        var view = service.getVpnPeers().get(0);

        assertThat(view.peerType()).isEqualTo(MachineType.defaultType());
        // No stored config means no name to resolve either: the address is all there is to show.
        assertThat(view.name()).isEqualTo(net.vaier.domain.PeerId.display("10.13.13.2"));
        assertThat(view.lanCidr()).isNull();
        assertThat(view.lanAddress()).isNull();
        assertThat(view.description()).isNull();
    }

    /**
     * The field the Explorer joins the fleet on. It comes from the stored config and is never minted here:
     * this is a read, and identity is read, never invented.
     */
    @Test
    void getVpnPeers_carriesTheMachinesIdentityFromItsStoredConfig() {
        MachineId identity = MachineId.generate();
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, null, null, null, null, null, identity, null)));

        assertThat(service.getVpnPeers().get(0).machineId()).isEqualTo(identity.value());
    }

    /**
     * A live WireGuard peer with no config on disk is in no machine registry, so it has no identity to give.
     * Null, never a stand-in: a fabricated id would join to nothing while looking like it could, and a
     * caller would read "this peer is not a machine" as "this machine is not a peer".
     */
    @Test
    void getVpnPeers_hasNoIdentityForAPeerWithNoStoredConfig() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        assertThat(service.getVpnPeers().get(0).machineId()).isNull();
    }

    // --- getVpnPeers: the last service reached ---

    /** The operator's question: which service did the phone open last, and when. */
    @Test
    void getVpnPeers_showsWhatThatMachineLastReached() {
        MachineId phone = MachineId.generate();
        Instant reachedAt = Instant.parse("2026-08-11T20:14:00Z");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0")));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.5", "", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, phone, null)));
        when(forPersistingLastServicesReached.getAll()).thenReturn(LastServicesReached.of(List.of(
            new LastServiceReached(phone, "grafana.example.com", reachedAt))));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            new ReverseProxyRoute("r", "grafana.example.com", "10.13.13.5", 3000, "svc", null)));

        assertThat(service.getVpnPeers().get(0).lastServiceReached()).hasValueSatisfying(reached -> {
            assertThat(reached.host()).isEqualTo("grafana.example.com");
            assertThat(reached.displayName()).isEqualTo("grafana");
            assertThat(reached.at()).isEqualTo(reachedAt);
        });
    }

    @Test
    void getVpnPeers_hasNoLastServiceForAMachineThatHasReachedNothing() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0")));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.5", "", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, MachineId.generate(), null)));

        assertThat(service.getVpnPeers().get(0).lastServiceReached()).isEmpty();
    }

    /** A peer with no stored config has no identity, so nothing can be attributed to it either. */
    @Test
    void getVpnPeers_hasNoLastServiceForAPeerWithNoStoredConfig() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0")));
        when(forPersistingLastServicesReached.getAll()).thenReturn(LastServicesReached.of(List.of(
            new LastServiceReached(MachineId.generate(), "grafana.example.com", Instant.now()))));

        assertThat(service.getVpnPeers().get(0).lastServiceReached()).isEmpty();
    }

    /** The console is a gated host too, and Vaier publishes no route for it. The host stands alone. */
    @Test
    void getVpnPeers_leavesTheLastServiceUnnamedWhenNoRouteServesThatHost() {
        MachineId phone = MachineId.generate();
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0")));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.5", "", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, phone, null)));
        when(forPersistingLastServicesReached.getAll()).thenReturn(LastServicesReached.of(List.of(
            new LastServiceReached(phone, "vaier.example.com", Instant.now()))));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getVpnPeers().get(0).lastServiceReached())
            .hasValueSatisfying(reached -> assertThat(reached.displayName()).isNull());
    }

    /** Both stores are read once per refresh, not once per peer — this list is refreshed on a clock. */
    @Test
    void getVpnPeers_readsTheReachStoreAndTheRoutesOncePerRefresh() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("a", "10.13.13.5/32", "", "", "0", "0", "0"),
            new VpnClient("b", "10.13.13.6/32", "", "", "0", "0", "0")));

        service.getVpnPeers();

        verify(forPersistingLastServicesReached, times(1)).getAll();
        verify(forPersistingReverseProxyRoutes, times(1)).getReverseProxyRoutes();
    }

    @Test
    void getVpnPeers_skipsGeolocationWhenEndpointIsBlank() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        var view = service.getVpnPeers().get(0);

        assertThat(view.geoLocation()).isEmpty();
        verify(forGeolocatingIps, never()).locate(any());
    }

    @Test
    void getVpnPeers_emptyGeoOptionalWhenLookupFails() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forGeolocatingIps.locate("203.0.113.10")).thenReturn(Optional.empty());

        assertThat(service.getVpnPeers().get(0).geoLocation()).isEmpty();
    }

    // --- reissuePeerConfig (#247) ---

    @Test
    void reissuePeerConfig_reRendersWithCurrentServerLanCidr_rewritesAndResetsGate() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        // A server peer created before server-LAN routing: its client AllowedIPs lacks the CIDR.
        String existing = net.vaier.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        var result = service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue())
            .contains("AllowedIPs = 10.13.13.0/24,172.31.16.0/20")
            .contains("PrivateKey = PRIVKEY");
        verify(forTrackingPeerConfigRetrieval).resetViewed("apalveien5");
        assertThat(result.clientConfigFile()).contains("172.31.16.0/20");
        assertThat(result.publicKey()).isEqualTo("PEER_PUB");
        assertThat(result.ipAddress()).isEqualTo("10.13.13.6");
    }

    @Test
    void reissuePeerConfig_throwsWhenPeerUnknown() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissuePeerConfig("ghost"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).rewriteConfig(any(), any());
    }

    @Test
    void reissuePeerConfig_retainsStoredDeviceCategoryOverride() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.vaier.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null,
                net.vaier.domain.DeviceCategory.NAS)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue()).contains("\"deviceCategory\":\"NAS\"");
    }

    @Test
    void reissuePeerConfig_nonOverriddenPeer_writesNoDeviceCategoryKey() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.vaier.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue()).doesNotContain("deviceCategory");
    }

    @Test
    void getVpnPeers_flagsConfigOutOfDateWhenRenderedConfigDiverges() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.vaier.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.eilertsen.family:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isTrue();
    }

    @Test
    void getVpnPeers_configNotOutOfDateWhenServerStateUnavailable() {
        // The tunnel cannot say its own key → drift can't be computed; must not false-flag.
        when(forGettingServerPublicKey.getServerPublicKey()).thenThrow(new RuntimeException("wg0 is down"));
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", "[Interface]",
                MachineType.UBUNTU_SERVER, null, null, null)));

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isFalse();
    }

    // --- site-to-site routing: every render sees the whole fleet (#250) ---

    private static final String COLINA_BEFORE_APALVEIEN = WireGuardPeerConfig.generate(
        "PRIVKEY", "10.13.13.3", "SERVER_PUB", "PSK", "vaier.eilertsen.family:51820",
        MachineType.UBUNTU_SERVER, "192.168.1.0/24", null, "10.13.13.0/24", null, "Colina 27",
        "172.31.16.0/20");

    private List<PeerConfiguration> colinaAndApalveien() {
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        List<PeerConfiguration> fleet = List.of(
            new PeerConfiguration("colina-27", "Colina 27", "10.13.13.3", COLINA_BEFORE_APALVEIEN,
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null, null),
            new PeerConfiguration("apalveien-5", "Apalveien 5", "10.13.13.6", "",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", null, null));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(fleet);
        return fleet;
    }

    @Test
    void getVpnPeers_aSiblingRelayJoining_marksTheRelaysConfigOutOfDate() {
        colinaAndApalveien();
        when(forGettingVpnClients.getClients()).thenReturn(List.of(
            new VpnClient("pub", "10.13.13.3/32", "", "", "0", "0", "0")));

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isTrue();
    }

    @Test
    void reissuePeerConfig_rendersAgainstTheWholeFleet() throws Exception {
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        List<PeerConfiguration> fleet = colinaAndApalveien();
        when(peerConfigProvider.getPeerConfigByName("colina-27")).thenReturn(Optional.of(fleet.get(0)));
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        service.reissuePeerConfig("colina-27");

        verify(forUpdatingPeerConfigurations).rewriteConfig("colina-27", WireGuardPeerConfig.reissue(
            COLINA_BEFORE_APALVEIEN, MachineType.UBUNTU_SERVER, "192.168.1.0/24", null, null, "Colina 27",
            "SERVER_PUB", "vaier.eilertsen.family:51820", "10.13.13.0/24", "172.31.16.0/20", null, fleet));
    }

    @Test
    void createPeer_aNewServerPeerRoutesEveryRelaysLan(@TempDir Path dir) throws Exception {
        wireguardIsReachable(dir);
        colinaAndApalveien();
        when(forExecutingInContainer.execute("wireguard", "wg", "genkey")).thenReturn("PRIV\n");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");
        when(forExecutingInContainer.executeWithInput("wireguard", "PRIV", "wg", "pubkey")).thenReturn("PUB\n");

        var created = service.createPeer("VPS", MachineType.UBUNTU_SERVER, null, null);

        assertThat(WireGuardPeerConfig.readDirective(created.clientConfigFile(), "AllowedIPs"))
            .isEqualTo("10.13.13.0/24,172.31.16.0/20,192.168.1.0/24,192.168.3.0/24");
    }


    // --- placement: a reported position beats the ISP estimate, and a dead tunnel places nothing ---

    /** A handshake WireGuard would still call live — the peer is connected right now. */
    private static String justNow() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private PeerConfiguration phoneConfig() {
        return new PeerConfiguration("phone", "Phone", "10.13.13.6", "[Interface]",
            MachineType.MOBILE_CLIENT, null, null, null, null, null, mid("phone"), null);
    }

    private void livePeerAt(String endpointIp, GeoLocation estimate) {
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", endpointIp, "51820", justNow(), "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(phoneConfig()));
        when(forGeolocatingIps.locate(endpointIp)).thenReturn(Optional.ofNullable(estimate));
    }

    @Test
    void getVpnPeers_placesAConnectedPeerWithNoReportAtItsIspEstimate() {
        livePeerAt("77.16.37.23", new GeoLocation(59.8989, 10.6324, "Oslo", "Norway"));

        assertThat(service.getVpnPeers().get(0).placement()).get().satisfies(placement -> {
            assertThat(placement.source()).isEqualTo(PlacementSource.ISP_ESTIMATE);
            assertThat(placement.latitude()).isEqualTo(59.8989);
            assertThat(placement.place()).isEqualTo("Oslo, Norway");
            assertThat(placement.stale()).isFalse();
        });
    }

    /**
     * The reported bug: the tunnel has been down for a day and a half and {@code wg} still names the
     * carrier IP it last saw. Vaier draws nothing rather than claiming the phone is at Fornebu.
     */
    @Test
    void getVpnPeers_placesNothingForADisconnectedPeerWithNoReport() {
        VpnClient stale = new VpnClient("pub", "10.13.13.6/32", "77.16.37.23", "51820",
            String.valueOf(System.currentTimeMillis() / 1000 - 35 * 3600), "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(stale));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            phoneConfig()));
        when(forGeolocatingIps.locate("77.16.37.23"))
            .thenReturn(Optional.of(new GeoLocation(59.8989, 10.6324, "Oslo", "Norway")));

        var view = service.getVpnPeers().get(0);

        assertThat(view.placement()).isEmpty();
        // The raw ISP estimate stays on the view — other things read it; only the placement is withheld.
        assertThat(view.geoLocation()).isPresent();
    }

    @Test
    void getVpnPeers_prefersTheDevicesOwnReportedPositionOverTheCarriersRegistryPoint() {
        livePeerAt("77.16.37.23", new GeoLocation(59.8989, 10.6324, "Oslo", "Norway"));
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("phone"))
                .withPosition(ReportedPosition.report(63.4305, 10.3951, 12.0, Instant.now())))));

        assertThat(service.getVpnPeers().get(0).placement()).get().satisfies(placement -> {
            assertThat(placement.source()).isEqualTo(PlacementSource.REPORTED);
            assertThat(placement.latitude()).isEqualTo(63.4305);
            assertThat(placement.accuracyMetres()).isEqualTo(12.0);
        });
    }

    // --- the position trail on the peer view ---

    @Test
    void getVpnPeers_carriesTheTrailOfWhereTheMachineHasBeen() {
        livePeerAt("77.16.37.23", new GeoLocation(59.8989, 10.6324, "Oslo", "Norway"));
        Instant now = Instant.now();
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.empty()
            .withPositionFor(mid("phone"),
                ReportedPosition.report(63.4305, 10.3951, 12.0, now.minusSeconds(3600)))
            .withPositionFor(mid("phone"), ReportedPosition.report(63.5305, 10.3951, 12.0, now)));

        assertThat(service.getVpnPeers().get(0).positionTrail().points())
            .extracting(ReportedPosition::latitude)
            .containsExactly(63.4305, 63.5305);
    }

    /** No report, no trail — and never one built from the carrier's registry point. */
    @Test
    void getVpnPeers_carriesAnEmptyTrailForAMachineThatOnlyHasAnIspEstimate() {
        livePeerAt("77.16.37.23", new GeoLocation(59.8989, 10.6324, "Oslo", "Norway"));

        assertThat(service.getVpnPeers().get(0).positionTrail().points()).isEmpty();
    }

    /** Retention is the domain's, and it has to hold on the way out too, not only on the way in. */
    @Test
    void getVpnPeers_leavesOutTrailPointsThatHaveAgedOut() {
        livePeerAt("77.16.37.23", new GeoLocation(59.8989, 10.6324, "Oslo", "Norway"));
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.empty()
            .withPositionFor(mid("phone"), ReportedPosition.report(63.4305, 10.3951, 12.0,
                Instant.now().minus(PositionTrail.RETENTION).minusSeconds(3600))));

        assertThat(service.getVpnPeers().get(0).positionTrail().points()).isEmpty();
    }

    // --- reportMyPosition: the tunnel, else a device claim, never the caller's say-so ---

    /** The store, having attributed the report to that machine — an answer only it is in a position to give. */
    private void attributedTo(MachineId machineId) {
        when(forPersistingMachinePositions.recordReportedPosition(any(), any(), any()))
            .thenReturn(Optional.of(machineId));
    }

    @Test
    void reportMyPosition_onTheTunnel_namesTheCallersOwnMachineAsTheTunnelIdentity() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(phoneConfig()));
        attributedTo(mid("phone"));

        service.reportMyPosition("10.13.13.6", null, 63.4305, 10.3951, 12.0);

        ArgumentCaptor<ReportedPosition> reported = ArgumentCaptor.forClass(ReportedPosition.class);
        verify(forPersistingMachinePositions)
            .recordReportedPosition(eq(mid("phone")), isNull(), reported.capture());
        assertThat(reported.getValue().latitude()).isEqualTo(63.4305);
        assertThat(reported.getValue().longitude()).isEqualTo(10.3951);
        assertThat(reported.getValue().accuracyMetres()).isEqualTo(12.0);
    }

    /**
     * The service says who is talking and lets the store work out whose report it is. Deciding that here
     * means deciding it against a read a {@code Forget} can invalidate before the write lands — the race
     * that filed a position, and a trail point, for a device the operator had just erased.
     */
    @Test
    void reportMyPosition_neverReadsTheStoreItIsAboutToWriteTo() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(phoneConfig()));
        attributedTo(mid("phone"));

        service.reportMyPosition("10.13.13.6", null, 63.4305, 10.3951, 12.0);

        verify(forPersistingMachinePositions, never()).getAll();
        // A report writes a position and nothing else, so it can neither revoke nor restore a claim.
        verify(forPersistingMachinePositions, never()).saveClaim(any(), any());
        verify(forPersistingMachinePositions, never()).remove(any());
    }

    /**
     * Being outside the VPN subnet settles it: no tunnel identity, whatever the peer store would answer
     * for that address. A carrier address is shared by thousands of subscribers, so a peer record that
     * happened to match one must never name the device reporting from it.
     */
    @Test
    void reportMyPosition_offTheTunnel_takesNoTunnelIdentityEvenWhenAPeerWouldMatchThatAddress() {
        lenient().when(peerConfigProvider.getPeerConfigByIp("77.16.37.23"))
            .thenReturn(Optional.of(phoneConfig()));
        attributedTo(mid("phone"));

        service.reportMyPosition("77.16.37.23", "claim-token", 63.4305, 10.3951, 12.0);

        verify(forPersistingMachinePositions)
            .recordReportedPosition(isNull(), eq("claim-token"), any());
    }

    /** Forget is guarded by the same rule — an off-tunnel caller may only erase what its claim names. */
    @Test
    void forgetMyPosition_offTheTunnel_takesNoTunnelIdentityEvenWhenAPeerWouldMatchThatAddress() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        lenient().when(peerConfigProvider.getPeerConfigByIp("77.16.37.23"))
            .thenReturn(Optional.of(phoneConfig()));
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("laptop")).withClaim(claim))));

        service.forgetMyPosition("77.16.37.23", claim.token());

        verify(forPersistingMachinePositions).remove(mid("laptop"));
    }

    /** An Android phone drops WireGuard constantly, so off-tunnel is the ordinary case, not the edge. */
    @Test
    void reportMyPosition_offTheTunnel_carriesTheClaimAndNoTunnelIdentity() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        attributedTo(mid("phone"));

        service.reportMyPosition("203.0.113.9", claim.token(), 63.4305, 10.3951, 12.0);

        verify(forPersistingMachinePositions).recordReportedPosition(isNull(), eq(claim.token()), any());
    }

    /** Both identities go over as they are: which one wins is the domain's rule, applied under the lock. */
    @Test
    void reportMyPosition_handsOverBothIdentitiesWithoutChoosingBetweenThem() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(phoneConfig()));
        attributedTo(mid("phone"));

        service.reportMyPosition("10.13.13.6", claim.token(), 63.4305, 10.3951, 12.0);

        verify(forPersistingMachinePositions)
            .recordReportedPosition(eq(mid("phone")), eq(claim.token()), any());
    }

    /**
     * Nothing identified the caller — no tunnel, and no claim the store still recognises, which covers a
     * revoked one and one a second claim superseded alike. Nothing was written, so nothing needs undoing.
     */
    @Test
    void reportMyPosition_refusesWhenTheStoreAttributedTheReportToNoMachine() {
        when(forPersistingMachinePositions.recordReportedPosition(any(), any(), any()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportMyPosition("203.0.113.9", "revoked", 63.4, 10.3, 12.0))
            .isInstanceOf(UnidentifiedDeviceException.class);
        verify(forPersistingMachinePositions, never()).saveClaim(any(), any());
        verify(forPersistingMachinePositions, never()).remove(any());
    }

    @Test
    void reportMyPosition_refusesACallerWithNoAddressAndNoClaim() {
        assertThatThrownBy(() -> service.reportMyPosition(null, null, 63.4305, 10.3951, 12.0))
            .isInstanceOf(UnidentifiedDeviceException.class);
        verify(forPersistingMachinePositions).recordReportedPosition(isNull(), isNull(), any());
    }

    @Test
    void reportMyPosition_rejectsCoordinatesOffTheGlobeWithoutTouchingTheStore() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(phoneConfig()));

        assertThatThrownBy(() -> service.reportMyPosition("10.13.13.6", null, 91.0, 10.3951, 12.0))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(forPersistingMachinePositions);
    }

    // --- forgetMyPosition: erasing the dot and forgetting the browser are one action ---

    @Test
    void forgetMyPosition_forgetsOnlyTheCallersOwnMachine() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(phoneConfig()));

        service.forgetMyPosition("10.13.13.6", null);

        verify(forPersistingMachinePositions).remove(mid("phone"));
    }

    @Test
    void forgetMyPosition_offTheTunnel_revokesTheClaimThatIdentifiedTheBrowser() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("phone")).withClaim(claim))));

        service.forgetMyPosition("203.0.113.9", claim.token());

        verify(forPersistingMachinePositions).remove(mid("phone"));
    }

    @Test
    void forgetMyPosition_refusesAnUnclaimedCallerOffTheTunnel() {
        assertThatThrownBy(() -> service.forgetMyPosition("203.0.113.9", null))
            .isInstanceOf(UnidentifiedDeviceException.class);
        verify(forPersistingMachinePositions, never()).remove(any());
    }

    // --- claimDevice: the operator's deliberate assertion from an authorised session ---

    /** Vaier knows its own machines, so the fleet is stubbed wherever a claim is expected to land. */
    private void fleetKnowsThePhone() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(phoneConfig()));
    }

    @Test
    void claimDevice_issuesATokenThatThenIdentifiesThatBrowsersMachine() {
        fleetKnowsThePhone();

        String token = service.claimDevice(mid("phone").value());

        ArgumentCaptor<DeviceClaim> saved = ArgumentCaptor.forClass(DeviceClaim.class);
        verify(forPersistingMachinePositions).saveClaim(eq(mid("phone")), saved.capture());
        assertThat(saved.getValue().matches(token)).isTrue();
    }

    /**
     * Claiming writes only the claim. Whether that supersedes an older one, and whether the machine's
     * position survives, are the store's merge — decided against what is on disk, not against a snapshot
     * this service read beforehand.
     */
    @Test
    void claimDevice_writesOnlyTheClaim_soAConcurrentReportIsNotReverted() {
        fleetKnowsThePhone();

        service.claimDevice(mid("phone").value());

        verify(forPersistingMachinePositions).saveClaim(eq(mid("phone")), any());
        verify(forPersistingMachinePositions, never()).recordReportedPosition(any(), any(), any());
        verify(forPersistingMachinePositions, never()).remove(any());
    }

    /** It must not even read the store to claim — a read it does not take cannot go stale. */
    @Test
    void claimDevice_neverReadsTheStoreItIsAboutToWriteTo() {
        fleetKnowsThePhone();

        service.claimDevice(mid("phone").value());

        verify(forPersistingMachinePositions, never()).getAll();
    }

    // --- myDevice: a property of the browser asking, not of the machine ---

    @Test
    void myDevice_namesTheMachineThatBrowsersClaimIsOn() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("phone")).withClaim(claim))));

        assertThat(service.myDevice(claim.token())).contains(mid("phone"));
    }

    @Test
    void myDevice_isEmptyForABrowserWithNoClaim() {
        assertThat(service.myDevice(null)).isEmpty();
        assertThat(service.myDevice("")).isEmpty();
        assertThat(service.myDevice("not-a-token")).isEmpty();
    }

    @Test
    void myDevice_isEmptyOnceTheClaimHasBeenRevoked() {
        DeviceClaim claim = DeviceClaim.mint(Instant.now());
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("phone")).withClaim(claim).withoutClaim())));

        assertThat(service.myDevice(claim.token())).isEmpty();
    }

    @Test
    void myDevice_isEmptyForATokenASecondClaimSuperseded() {
        DeviceClaim first = DeviceClaim.mint(Instant.now());
        DeviceClaim second = DeviceClaim.mint(Instant.now());
        when(forPersistingMachinePositions.getAll()).thenReturn(MachinePositions.of(List.of(
            MachinePosition.forMachine(mid("phone")).withClaim(first).withClaim(second))));

        assertThat(service.myDevice(first.token())).isEmpty();
        assertThat(service.myDevice(second.token())).contains(mid("phone"));
    }

    /** Being on the tunnel lets a device report; it is not a claim, and must not read as one. */
    @Test
    void myDevice_isEmptyForATunnelCallerThatHasNeverBeenClaimed() {
        assertThat(service.myDevice(null)).isEmpty();
        verifyNoInteractions(peerConfigProvider);
    }

    @Test
    void claimDevice_rejectsSomethingThatIsNotAMachineId() {
        assertThatThrownBy(() -> service.claimDevice("phone"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPersistingMachinePositions, never()).saveClaim(any(), any());
    }

    /**
     * A well-formed id for a machine that is not there would store a claim that can never place a dot:
     * an action Vaier already knows cannot work, accepted anyway. Vaier knows its own fleet, so it checks.
     */
    @Test
    void claimDevice_rejectsAMachineIdThatNamesNoPeer() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(phoneConfig()));

        assertThatThrownBy(() -> service.claimDevice(mid("ghost").value()))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPersistingMachinePositions, never()).saveClaim(any(), any());
    }

    @Test
    void claimDevice_rejectsAnyMachineIdWhenThereAreNoPeers() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());

        assertThatThrownBy(() -> service.claimDevice(mid("phone").value()))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forPersistingMachinePositions, never()).saveClaim(any(), any());
    }

    // --- enrol: a phone joins with a key born on the device (#359 slice 1) ---

    /** A real, structurally valid WireGuard public key, as the app would present it. */
    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";

    private void wireguardIsReachable(Path configDir) {
        ReflectionTestUtils.setField(service, "wireguardConfigPath", configDir.toString());
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
    }

    @Test
    void enrol_writesAConfigWithNoPrivateKeyAndTheDevicesOwnPublicKey(@TempDir Path dir)
            throws Exception {
        wireguardIsReachable(dir);
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        var enrolled = service.enrol("Geir's phone", DEVICE_KEY);

        // The private key was minted on the phone and must never appear anywhere here.
        assertThat(enrolled.configFile()).doesNotContain("PrivateKey");
        assertThat(enrolled.configFile()).contains("\"publicKey\":\"" + DEVICE_KEY + "\"");
        assertThat(enrolled.configFile()).contains("PresharedKey = PSK");
        assertThat(enrolled.publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(enrolled.name()).isEqualTo("Geir's phone");
        assertThat(enrolled.peerType()).isEqualTo(MachineType.MOBILE_CLIENT);
        assertThat(enrolled.ipAddress()).isEqualTo("10.13.13.2");
        assertThat(enrolled.machineId()).isNotNull();

        // What lands on disk is what was handed back.
        String onDisk = Files.readString(
            dir.resolve(enrolled.id()).resolve(enrolled.id() + ".conf"));
        assertThat(onDisk).isEqualTo(enrolled.configFile());
    }

    @Test
    void enrol_installsTheNewPeersRoute_insteadOfRestartingTheInterface(@TempDir Path dir) throws Exception {
        // Every add used to bounce the whole WireGuard container to get a kernel route for one peer,
        // dropping every other tunnel for seconds each time. The route is installed by itself now.
        wireguardIsReachable(dir);
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        service.enrol("Geir's phone", DEVICE_KEY);

        verify(forUpdatingServerAllowedIps).installRoutes("10.13.13.2/32");
    }

    @Test
    void enrol_neverGeneratesAKeypair(@TempDir Path dir) throws Exception {
        wireguardIsReachable(dir);
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        service.enrol("phone", DEVICE_KEY);

        // The whole point: no private key is ever minted here, and none is ever derived from.
        verify(forExecutingInContainer, never()).execute(any(), eq("wg"), eq("genkey"));
        verify(forExecutingInContainer, never()).executeWithInput(any(), any(), eq("wg"), eq("pubkey"));
        // The preshared key is shared, so Vaier still generates it and ships it in the config.
        verify(forExecutingInContainer).execute("wireguard", "wg", "genpsk");
    }

    @Test
    void enrol_addsTheDevicesOwnKeyToTheServer(@TempDir Path dir) throws Exception {
        wireguardIsReachable(dir);
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        service.enrol("phone", DEVICE_KEY);

        verify(forExecutingInContainer).execute(eq("wireguard"), eq("wg"), eq("set"), eq("wg0"),
            eq("peer"), eq(DEVICE_KEY), eq("preshared-key"), any(), eq("allowed-ips"), eq("10.13.13.2/32"));
    }

    @Test
    void enrol_spendsTheOneShotRetrievalBudget(@TempDir Path dir) throws Exception {
        // The config is handed to the app in the enrolment response. There is nothing left to retrieve,
        // and a peer with a device-held key has no artefact at all — so the five GET endpoints must
        // answer 410 from the first moment rather than serving a config without a private key.
        wireguardIsReachable(dir);
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        var enrolled = service.enrol("phone", DEVICE_KEY);

        verify(forTrackingPeerConfigRetrieval).markViewedIfNotAlready(enrolled.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-a-key",
        "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=; rm -rf /",
        "$(id)",
        "c2hvcnQ="
    })
    void enrol_rejectsAKeyThatIsNotAWireGuardKey_beforeAnythingMoves(String malicious) {
        // Before any state change, exactly as createPeer validates a lanCidr: the key goes straight into
        // `wg set ... peer <key>`'s argv and into the config on disk.
        assertThatThrownBy(() -> service.enrol("phone", malicious))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(peerConfigProvider, forExecutingInContainer, forUpdatingPeerConfigurations,
            forTrackingPeerConfigRetrieval);
    }

    @Test
    void enrol_rejectsABlankName() {
        assertThatThrownBy(() -> service.enrol("  ", DEVICE_KEY))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(peerConfigProvider, forExecutingInContainer);
    }

    @Test
    void reissuePeerConfig_refusesADeviceHeldKey_andSaysWhatToDoInstead() {
        // A Reissue re-renders an installable config. For a phone that minted its own key there is
        // nothing installable to re-render — the private half exists only on the device — so the
        // operation is refused before it touches the peer's file at all.
        String existing = WireGuardPeerConfig.generate(
            null, "10.13.13.7", "OLD_PUB", "PSK", "old.example.com:51820",
            MachineType.MOBILE_CLIENT, null, null, "10.13.13.0/24", null, "Ruten", null, null,
            mid("ruten"), DEVICE_KEY);
        when(peerConfigProvider.getPeerConfigByName("ruten")).thenReturn(Optional.of(
            new PeerConfiguration("ruten", "Ruten", "10.13.13.7", existing, MachineType.MOBILE_CLIENT,
                null, null, null, null, null, mid("ruten"), DEVICE_KEY)));

        assertThatThrownBy(() -> service.reissuePeerConfig("ruten"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Ruten")
            .hasMessageContaining("enrol it again");

        verify(forUpdatingPeerConfigurations, never()).rewriteConfig(any(), any());
        verify(forTrackingPeerConfigRetrieval, never()).resetViewed(any());
        verifyNoInteractions(forExecutingInContainer);
    }

    // --- enrolment requests: a phone waits, the operator approves from anywhere (#359 slice 1b) ---

    private static final String PSK = "cGKrDp0z0Fs0IiUrPzuTfnJ7CEZzSXpGX0ZlLBFgLGE=";

    private EnrolmentRequest waitingRequest(String code, String ticket) {
        return EnrolmentRequest.open("Ruten", DEVICE_KEY, code, ticket, System.currentTimeMillis());
    }

    @Test
    void request_opensARequestWhileFewerThanFivePhonesAreWaiting() {
        EnrolmentRequest opened = waitingRequest("4821", "ticket-1");
        when(forHoldingEnrolmentRequests.livePending()).thenReturn(List.of());
        when(forHoldingEnrolmentRequests.open("Ruten", DEVICE_KEY)).thenReturn(opened);

        assertThat(service.request("Ruten", DEVICE_KEY)).isEqualTo(opened);
    }

    @Test
    void request_refusesASixthWaitingPhone() {
        // The size of the anonymous surface is the whole safety argument; the cap is the domain's.
        when(forHoldingEnrolmentRequests.livePending()).thenReturn(List.of(
            waitingRequest("0001", "t1"), waitingRequest("0002", "t2"), waitingRequest("0003", "t3"),
            waitingRequest("0004", "t4"), waitingRequest("0005", "t5")));

        assertThatThrownBy(() -> service.request("Ruten", DEVICE_KEY))
            .isInstanceOf(ConflictException.class);

        verify(forHoldingEnrolmentRequests, never()).open(any(), any());
    }

    @Test
    void pending_isWhateverIsStillWaiting() {
        EnrolmentRequest waiting = waitingRequest("4821", "ticket-1");
        when(forHoldingEnrolmentRequests.livePending()).thenReturn(List.of(waiting));

        assertThat(service.pending()).containsExactly(waiting);
    }

    @Test
    void approve_enrolsTheDeviceUnderTheKeyItPresented_andRecordsTheConfigOnTheRequest(@TempDir Path dir)
            throws Exception {
        wireguardIsReachable(dir);
        when(forHoldingEnrolmentRequests.findByCode("4821"))
            .thenReturn(Optional.of(waitingRequest("4821", "ticket-1")));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
        when(forGettingServerPublicKey.getServerPublicKey()).thenReturn("SERVER_PUB");
        when(forExecutingInContainer.execute("wireguard", "wg", "genpsk")).thenReturn("PSK\n");

        var approved = service.approve("4821");

        assertThat(approved.ticket()).isEqualTo("ticket-1");
        assertThat(approved.device().name()).isEqualTo("Ruten");
        assertThat(approved.device().publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(approved.device().configFile()).doesNotContain("PrivateKey");
        // The config stays on the request, so a phone whose stream dropped mid-approval still gets it.
        verify(forHoldingEnrolmentRequests).recordApproval("4821", approved.device().configFile());
    }

    @Test
    void approve_anUnknownOrExpiredCode_isNotFound() {
        when(forHoldingEnrolmentRequests.findByCode("0000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve("0000"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void approve_leavesTheRequestWaitingWhenTheEnrolmentFails() {
        when(forHoldingEnrolmentRequests.findByCode("4821"))
            .thenReturn(Optional.of(waitingRequest("4821", "ticket-1")));
        when(peerConfigProvider.getAllPeerConfigs()).thenThrow(new IllegalStateException("wireguard is down"));

        assertThatThrownBy(() -> service.approve("4821")).isInstanceOf(IllegalStateException.class);

        // Nothing was approved, so the phone keeps waiting and the operator can try again.
        verify(forHoldingEnrolmentRequests, never()).recordApproval(any(), any());
    }

    @Test
    void refuse_removesTheRequestAndHandsBackItsTicket() {
        EnrolmentRequest waiting = waitingRequest("4821", "ticket-1");
        when(forHoldingEnrolmentRequests.remove("4821")).thenReturn(Optional.of(waiting));

        assertThat(service.refuse("4821")).contains(waiting);
    }

    @Test
    void refuse_anUnknownCode_isANoOp() {
        when(forHoldingEnrolmentRequests.remove("0000")).thenReturn(Optional.empty());

        assertThat(service.refuse("0000")).isEmpty();
    }

    @Test
    void lookUp_anUnknownTicket_isNothing() {
        when(forHoldingEnrolmentRequests.findByTicket("made-up")).thenReturn(Optional.empty());

        assertThat(service.lookUp("made-up").isGone()).isTrue();
    }

    @Test
    void lookUp_aWaitingTicket_isPending() {
        when(forHoldingEnrolmentRequests.findByTicket("ticket-1"))
            .thenReturn(Optional.of(waitingRequest("4821", "ticket-1")));

        assertThat(service.lookUp("ticket-1").isPending()).isTrue();
    }

    @Test
    void lookUp_anApprovedTicket_carriesTheConfig() {
        when(forHoldingEnrolmentRequests.findByTicket("ticket-1"))
            .thenReturn(Optional.of(waitingRequest("4821", "ticket-1").approved("[Interface]")));

        assertThat(service.lookUp("ticket-1").configFile()).isEqualTo("[Interface]");
    }

    // --- leave: a phone removes itself from the fleet (#359 slice 1b) ---

    private PeerConfiguration enrolledPhone(String id, String publicKey, String presharedKey) {
        String config = "# VAIER: {}\n[Interface]\nAddress = 10.13.13.7/32\n\n[Peer]\n"
            + "PublicKey = SERVER_PUB\nPresharedKey = " + presharedKey + "\n";
        return new PeerConfiguration(id, id, "10.13.13.7", config, MachineType.MOBILE_CLIENT,
            null, null, null, null, null, mid(id), publicKey);
    }

    @Test
    void leave_removesThePeerWhoseConfigTheCallerProvesItHolds() {
        when(peerConfigProvider.getAllPeerConfigs())
            .thenReturn(List.of(enrolledPhone("ruten", DEVICE_KEY, PSK)));

        assertThat(service.leave(DEVICE_KEY, PSK)).isTrue();

        // The same cascade an operator's delete runs — published services first, then the peer.
        verify(vpnPeerDeleter).deletePeer("ruten");
    }

    @Test
    void isMember_isTrueForThePeerTheCallerProvesItHolds_andChangesNothing() {
        when(peerConfigProvider.getAllPeerConfigs())
            .thenReturn(List.of(enrolledPhone("ruten", DEVICE_KEY, PSK)));

        assertThat(service.isMember(DEVICE_KEY, PSK)).isTrue();
        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void isMember_isFalseOnceThePeerIsGone_orForTheWrongPresharedKey() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        assertThat(service.isMember(DEVICE_KEY, PSK)).isFalse();

        when(peerConfigProvider.getAllPeerConfigs())
            .thenReturn(List.of(enrolledPhone("ruten", DEVICE_KEY, PSK)));
        assertThat(service.isMember(DEVICE_KEY, "not-the-preshared-key")).isFalse();
    }

    @Test
    void leave_withTheWrongPresharedKey_removesNothing() {
        when(peerConfigProvider.getAllPeerConfigs())
            .thenReturn(List.of(enrolledPhone("ruten", DEVICE_KEY, PSK)));

        assertThat(service.leave(DEVICE_KEY, "not-the-preshared-key")).isFalse();

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void leave_isNotOfferedToAPeerThatDidNotMakeItsOwnKey() {
        when(peerConfigProvider.getAllPeerConfigs())
            .thenReturn(List.of(enrolledPhone("nuc02", null, PSK)));

        assertThat(service.leave(DEVICE_KEY, PSK)).isFalse();

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void leave_withoutBothKeys_isRefused() {
        assertThatThrownBy(() -> service.leave(DEVICE_KEY, null))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(peerConfigProvider, vpnPeerDeleter);
    }
}
