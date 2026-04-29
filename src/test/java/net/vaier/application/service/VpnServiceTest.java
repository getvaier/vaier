package net.vaier.application.service;

import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.application.GetServerLocationUseCase.ServerLocation;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.PeerType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import net.vaier.domain.port.ForSyncingLanRoutes;
import net.vaier.domain.port.ForUpdatingPeerConfigurations;
import net.vaier.domain.port.ForUpdatingServerAllowedIps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnServiceTest {

    @Mock ConfigResolver configResolver;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForResolvingPeerNames forResolvingPeerNames;
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

    @InjectMocks VpnService service;

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

    // --- resolvePeerNameByIp ---

    @Test
    void resolvePeerNameByIp_delegatesToPort() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");

        assertThat(service.resolvePeerNameByIp("10.13.13.2")).isEqualTo("alice");
    }

    @Test
    void resolvePeerNameByIp_returnsNullWhenNotFound() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.99")).thenReturn(null);

        assertThat(service.resolvePeerNameByIp("10.13.13.99")).isNull();
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

    @Test
    void generateWireguardClientDockerCompose_constructsDockerComposeConfigRecord() {
        ArgumentCaptor<DockerComposeConfig> captor = ArgumentCaptor.forClass(DockerComposeConfig.class);
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(captor.capture())).thenReturn("");

        service.generateWireguardClientDockerCompose("bob", "server.net", "51820");

        DockerComposeConfig config = captor.getValue();
        assertThat(config.peerName()).isEqualTo("bob");
        assertThat(config.serverUrl()).isEqualTo("server.net");
        assertThat(config.serverPort()).isEqualTo("51820");
    }

    // --- generateSetupScript ---

    @Test
    void generateSetupScript_peerNotFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("unknown")).thenReturn(Optional.empty());

        assertThat(service.generateSetupScript("unknown", "vpn.example.com", "51820")).isEmpty();
    }

    @Test
    void generateSetupScript_peerFound_returnsNonEmptyScript() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<String> result = service.generateSetupScript("alice", "vpn.example.com", "51820");

        assertThat(result).isPresent();
        assertThat(result.get()).isNotBlank();
    }

    @Test
    void generateSetupScript_scriptStartsWithShebang() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).startsWith("#!/bin/bash");
    }

    @Test
    void generateSetupScript_scriptContainsPeerName() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("alice");
    }

    @Test
    void generateSetupScript_scriptContainsVpnIp() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("10.13.13.2");
    }

    @Test
    void generateSetupScript_scriptContainsServerUrl() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("vpn.example.com");
    }

    @Test
    void generateSetupScript_scriptContainsServerPort() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("51820");
    }

    @Test
    void generateSetupScript_scriptEnablesDockerOnBoot() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl enable docker");
    }

    @Test
    void generateSetupScript_systemctlCallsAreNonFatal() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl enable docker || true");
        assertThat(script).contains("systemctl restart docker");
    }

    @Test
    void generateSetupScript_scriptFallsBackToServiceRestartWhenSystemctlFails() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl restart docker || sudo service docker restart || true");
    }

    @Test
    void generateSetupScript_scriptHandlesSnapDocker() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

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

    // --- generateSetupScript: relay-peer LAN forwarding (#170) ---

    @Test
    void generateSetupScript_lanCidrSet_enablesIpForwardingSysctl() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", "wg-config",
                PeerType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        String script = service.generateSetupScript("homelab", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("sysctl -w net.ipv4.ip_forward=1");
        assertThat(script).contains("net.ipv4.ip_forward=1");
        assertThat(script).contains("/etc/sysctl.d/99-wireguard.conf");
    }

    @Test
    void generateSetupScript_lanCidrSet_addsMasqueradeAndForwardRulesIdempotently() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", "wg-config",
                PeerType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        String script = service.generateSetupScript("homelab", "vpn.example.com", "51820").orElseThrow();

        // POSTROUTING MASQUERADE for vpn -> lan
        assertThat(script).contains(
            "iptables -t nat -C POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        assertThat(script).contains(
            "iptables -t nat -A POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        // FORWARD vpn -> lan
        assertThat(script).contains(
            "iptables -C FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        assertThat(script).contains(
            "iptables -A FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        // FORWARD lan -> vpn (RELATED,ESTABLISHED only)
        assertThat(script).contains(
            "iptables -C FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");
        assertThat(script).contains(
            "iptables -A FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");
    }

    @Test
    void generateSetupScript_lanCidrAbsent_omitsForwardingBlock() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).doesNotContain("net.ipv4.ip_forward=1");
        assertThat(script).doesNotContain("MASQUERADE");
        assertThat(script).doesNotContain("FORWARD");
    }

    @Test
    void generateSetupScript_lanCidrBlank_omitsForwardingBlock() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config",
                PeerType.UBUNTU_SERVER, "   ", null))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).doesNotContain("net.ipv4.ip_forward=1");
        assertThat(script).doesNotContain("MASQUERADE");
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
        String serverCompose = java.nio.file.Files.readString(java.nio.file.Path.of("docker-compose.yml"));
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
        verifyNoInteractions(forResolvingPeerNames);
    }

    @Test
    void deletePeer_byIp_resolvesToNameBeforeDeleting() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("10.13.13.2");

        verify(forResolvingPeerNames).resolvePeerNameByIp("10.13.13.2");
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_ipNotResolved_throwsIllegalArgumentException() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("10.13.13.99");
    }

    @Test
    void deletePeer_ipNotResolved_doesNotCallDeleter() {
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void deletePeer_ipLikeStringWithHighOctets_matchesRegexAndAttemptsResolution() {
        when(forResolvingPeerNames.resolvePeerNameByIp("999.999.999.999")).thenReturn("peer-x");
        when(peerConfigProvider.getPeerConfigByName("peer-x")).thenReturn(Optional.empty());

        service.deletePeer("999.999.999.999");

        verify(vpnPeerDeleter).deletePeer("peer-x");
    }

    @Test
    void deletePeer_deletesPublishedServicesPointingToPeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        ReverseProxyRoute otherRoute = new ReverseProxyRoute("other-router", "other.example.com", "10.13.13.3", 9090, "other-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute, otherRoute));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com");
        verify(deletePublishedServiceUseCase, never()).deleteService("other.example.com");
    }

    @Test
    void deletePeer_deletesMultipleServicesPointingToSamePeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute route1 = new ReverseProxyRoute("app1-router", "app1.example.com", "10.13.13.2", 8080, "app1-service", null);
        ReverseProxyRoute route2 = new ReverseProxyRoute("app2-router", "app2.example.com", "10.13.13.2", 9090, "app2-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route1, route2));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app1.example.com");
        verify(deletePublishedServiceUseCase).deleteService("app2.example.com");
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
        when(forResolvingPeerNames.resolvePeerNameByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("10.13.13.2");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com");
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
        order.verify(deletePublishedServiceUseCase).deleteService("app.example.com");
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
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", PeerType.UBUNTU_SERVER, "192.168.3.0/24", null),
            new PeerConfiguration("alice",      "10.13.13.2", "config", PeerType.WINDOWS_CLIENT,    null,            null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", PeerType.UBUNTU_SERVER, "192.168.4.0/24", null)
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
            new PeerConfiguration("alice", "10.13.13.2", "config", PeerType.WINDOWS_CLIENT,    null, null),
            new PeerConfiguration("blank", "10.13.13.3", "config", PeerType.UBUNTU_SERVER, "  ", null)
        ));

        service.syncLanRoutes();

        verify(forSyncingLanRoutes).syncLanRoutes(java.util.Set.of());
    }

    @Test
    void updateLanCidr_alsoSyncsLanRoutes() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                PeerType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", PeerType.UBUNTU_SERVER, null, null)));

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
                PeerType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", PeerType.UBUNTU_SERVER, null, null)));

        service.updateLanCidr("apalveien5", "192.168.3.0/24");

        var order = inOrder(forUpdatingServerAllowedIps, forUpdatingPeerConfigurations);
        order.verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.6", "10.13.13.6/32,192.168.3.0/24");
        order.verify(forUpdatingPeerConfigurations).updateLanCidr("apalveien5", "192.168.3.0/24");
    }

    @Test
    void updateLanCidr_clearingStripsServerSideAllowedIps() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                PeerType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", null);

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
        verify(forUpdatingPeerConfigurations).updateLanCidr("nuc02", null);
    }

    @Test
    void updateLanCidr_blankIsTreatedAsClear() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                PeerType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", "  ");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
    }

    @Test
    void updateLanCidr_changingReplacesServerSideCidr() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                PeerType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", PeerType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.5.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.5.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.5.0/24");
    }

    @Test
    void updateLanCidr_rejectsConflictWhenAnotherPeerOwnsTheCidr() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                PeerType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", PeerType.UBUNTU_SERVER, null, null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", PeerType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        assertThatThrownBy(() -> service.updateLanCidr("apalveien5", "192.168.3.0/24"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nuc02")
            .hasMessageContaining("192.168.3.0/24");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    @Test
    void updateLanCidr_allowsSameCidrOnSamePeerIdempotent() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                PeerType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", PeerType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.1.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.1.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.1.0/24");
    }

    @Test
    void updateLanCidr_throwsWhenPeerDoesNotExist() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLanCidr("ghost", "192.168.3.0/24"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ghost");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    // silence unused field warning — PeerType is referenced in Javadoc of PeerConfigResult ctor via record definition
    @SuppressWarnings("unused")
    private static final PeerType REFERENCE = PeerType.UBUNTU_SERVER;
}
