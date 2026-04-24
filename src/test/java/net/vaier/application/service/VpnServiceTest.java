package net.vaier.application.service;

import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetPeerConfigUseCase.PeerConfigResult;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.PeerType;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.VpnClient;
import net.vaier.domain.port.ForDeletingVpnPeers;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles;
import net.vaier.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForGettingVpnClients;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingPeerNames;
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

    // silence unused field warning — PeerType is referenced in Javadoc of PeerConfigResult ctor via record definition
    @SuppressWarnings("unused")
    private static final PeerType REFERENCE = PeerType.UBUNTU_SERVER;
}
