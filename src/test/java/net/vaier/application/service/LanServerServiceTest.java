package net.vaier.application.service;

import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerServiceTest {

    @Mock private ForPersistingLanServers forPersistingLanServers;
    @Mock private ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock private ForResolvingServerLanCidr forResolvingServerLanCidr;
    @Mock private ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    @Mock private DeletePublishedServiceUseCase deletePublishedServiceUseCase;

    @InjectMocks private LanServerService service;

    private static ReverseProxyRoute lanRoute(String name, String fqdn, String address, int port,
                                              String pathPrefix) {
        return new ReverseProxyRoute(name, fqdn, address, port, name + "-service", null, null, null,
            null, null, false, true, "http", pathPrefix);
    }

    @BeforeEach
    void setUp() {
        // register()/getAll() resolve the server LAN CIDR; default it to "absent" so the relay-only
        // tests behave as before. Tests exercising the server-LAN-CIDR path override it.
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
    }

    private static PeerConfiguration relay(String name, String ip, String lanCidr) {
        return new PeerConfiguration(name, ip, "", MachineType.UBUNTU_SERVER, lanCidr, null);
    }

    // --- register ---

    @Test
    void register_runsDockerTrue_lanAddressInsideRelayLanCidr_persists() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("nas", "192.168.3.50", true, 2375);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new LanServer("nas", "192.168.3.50", true, 2375));
    }

    @Test
    void register_runsDockerFalse_lanAddressInsideRelayLanCidr_persists() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("printer", "192.168.3.20", false, null);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new LanServer("printer", "192.168.3.20", false, null));
    }

    @Test
    void register_lanAddressOutsideAllRelayLanCidrs_throwsAndDoesNotPersist() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        assertThatThrownBy(() -> service.register("nas", "10.99.99.99", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
        verify(forPersistingLanServers, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void register_rejectsNameAlreadyUsedByAnotherLanServer() {
        // #284: machine names are unique across Vaier. save() upserts by name, so without this
        // guard a second register silently overwrites the first — data loss.
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)
        ));

        assertThatThrownBy(() -> service.register("nas", "192.168.3.51", true, 2375))
            .isInstanceOf(ConflictException.class);
        verify(forPersistingLanServers, never()).save(any());
    }

    @Test
    void register_rejectsNameAlreadyUsedByAVpnPeer() {
        // #284: a LAN server may not reuse a VPN peer's name either.
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        assertThatThrownBy(() -> service.register("apalveien5", "192.168.3.50", true, 2375))
            .isInstanceOf(ConflictException.class);
        verify(forPersistingLanServers, never()).save(any());
    }

    @Test
    void register_trimsSurroundingWhitespaceFromNameAndAddress() {
        // #284 review: the persisted identity must match the trimmed uniqueness-comparison rule,
        // and stay a clean /lan-servers/{name} path segment.
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("  nas  ", " 192.168.3.50 ", true, 2375);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("nas");
        assertThat(captor.getValue().lanAddress()).isEqualTo("192.168.3.50");
    }

    @Test
    void register_runsDockerTrueWithoutPort_throws() {
        assertThatThrownBy(() -> service.register("nas", "192.168.3.50", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
        verify(forPersistingLanServers, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void register_runsDockerTrueWithPortOutOfRange_throws() {
        assertThatThrownBy(() -> service.register("nas", "192.168.3.50", true, 70000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
        verify(forPersistingLanServers, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void register_blankName_throws() {
        assertThatThrownBy(() -> service.register("", "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void register_noRelayPeersExist_throws() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        assertThatThrownBy(() -> service.register("nas", "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_lanAddressInsideServerLanCidr_noRelayPeers_persists() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        service.register("vpc-box", "172.31.5.20", true, 2375);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new LanServer("vpc-box", "172.31.5.20", true, 2375));
    }

    @Test
    void register_lanAddressOutsideRelaysAndServerLanCidr_throwsAndDoesNotPersist() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        assertThatThrownBy(() -> service.register("x", "10.99.99.99", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
        verify(forPersistingLanServers, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // --- resolveLanAnchor ---

    @Test
    void resolveLanAnchor_addressInsideRelayLanCidr_returnsRelayAnchor() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        var anchor = service.resolveLanAnchor("192.168.3.50");

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isFalse();
        assertThat(anchor.get().name()).isEqualTo("apalveien5");
        assertThat(anchor.get().cidr()).isEqualTo("192.168.3.0/24");
    }

    @Test
    void resolveLanAnchor_addressInsideServerLanCidr_returnsVaierServerAnchor() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        var anchor = service.resolveLanAnchor("172.31.5.20");

        assertThat(anchor).isPresent();
        assertThat(anchor.get().isVaierServer()).isTrue();
        assertThat(anchor.get().name()).isEqualTo("Vaier server");
        assertThat(anchor.get().cidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void resolveLanAnchor_addressInNeither_empty() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        assertThat(service.resolveLanAnchor("10.99.99.99")).isEmpty();
    }

    @Test
    void resolveLanAnchor_blankOrNullAddress_empty() {
        assertThat(service.resolveLanAnchor("")).isEmpty();
        assertThat(service.resolveLanAnchor("   ")).isEmpty();
        assertThat(service.resolveLanAnchor(null)).isEmpty();
    }

    // --- delete ---

    @Test
    void delete_callsAdapterWithName() {
        // Unknown server (no matching LanServer): no cascade, but still deletes the record.
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        service.delete("nas");

        verify(forPersistingLanServers).deleteByName("nas");
        verify(deletePublishedServiceUseCase, never()).deleteService(any(), any());
    }

    @Test
    void delete_cascadesIntoPublishedServiceOnMatchingLanAddress() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("pump", "192.168.1.101", false, null)));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            lanRoute("pump-router", "pump.eilertsen.family", "192.168.1.101", 80, null)));

        service.delete("pump");

        verify(deletePublishedServiceUseCase).deleteService("pump.eilertsen.family", null);
        verify(forPersistingLanServers).deleteByName("pump");
    }

    @Test
    void delete_doesNotDeleteRouteBelongingToADifferentMachine() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("pump", "192.168.1.101", false, null)));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            lanRoute("nas-router", "nas.eilertsen.family", "192.168.1.50", 80, null)));

        service.delete("pump");

        verify(deletePublishedServiceUseCase, never()).deleteService(any(), any());
        verify(forPersistingLanServers).deleteByName("pump");
    }

    @Test
    void delete_noMatchingRoutes_deletesRecordAndNeverCascades() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("pump", "192.168.1.101", false, null)));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.delete("pump");

        verify(deletePublishedServiceUseCase, never()).deleteService(any(), any());
        verify(forPersistingLanServers).deleteByName("pump");
    }

    @Test
    void delete_cascadesIntoEveryRouteOnTheSameLanAddress() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("pump", "192.168.1.101", false, null)));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(
            lanRoute("pump-router", "pump.eilertsen.family", "192.168.1.101", 80, null),
            lanRoute("pump-ui-router", "pump.eilertsen.family", "192.168.1.101", 8080, "/ui")));

        service.delete("pump");

        verify(deletePublishedServiceUseCase).deleteService("pump.eilertsen.family", null);
        verify(deletePublishedServiceUseCase).deleteService("pump.eilertsen.family", "/ui");
        verify(forPersistingLanServers).deleteByName("pump");
    }

    // --- getAll ---

    @Test
    void getAll_resolvesRelayPeerNameForEachServer() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        List<LanServerView> views = service.getAll();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).server()).isEqualTo(new LanServer("nas", "192.168.3.50", true, 2375));
        assertThat(views.get(0).relayPeerName()).isEqualTo("apalveien5");
    }

    @Test
    void getAll_relayWasDeleted_returnsServerWithNullRelayName() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        List<LanServerView> views = service.getAll();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).relayPeerName()).isNull();
    }

    @Test
    void getAll_serverAnchoredLanServer_relayNameIsVaierServer() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("vpc-box", "172.31.5.20", true, 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        List<LanServerView> views = service.getAll();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).relayPeerName()).isEqualTo("Vaier server");
    }

    @Test
    void getAll_relayPeerWinsOverServerLanCidrOnOverlap() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("192.168.0.0/16"));

        assertThat(service.getAll().get(0).relayPeerName()).isEqualTo("apalveien5");
    }

    // --- rename (#55) ---

    @Test
    void rename_persistsNewNameAndRemovesOldKeepingAddressAndDockerSettings() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", true, 2375)));

        service.rename("nas", "media-nas");

        ArgumentCaptor<LanServer> saved = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("media-nas");
        assertThat(saved.getValue().lanAddress()).isEqualTo("192.168.1.50");
        assertThat(saved.getValue().runsDocker()).isTrue();
        assertThat(saved.getValue().dockerPort()).isEqualTo(2375);
        verify(forPersistingLanServers).deleteByName("nas");
    }

    @Test
    void rename_throwsWhenLanServerNotFound() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.rename("ghost", "phantom"))
            .isInstanceOf(NotFoundException.class);
        verify(forPersistingLanServers, never()).save(any());
        verify(forPersistingLanServers, never()).deleteByName(any());
    }

    @Test
    void rename_rejectsNameAlreadyUsedByAnotherLanServer() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.1.50", false, null),
            new LanServer("printer", "192.168.1.60", false, null)
        ));

        assertThatThrownBy(() -> service.rename("nas", "printer"))
            .isInstanceOf(ConflictException.class);
        verify(forPersistingLanServers, never()).save(any());
        verify(forPersistingLanServers, never()).deleteByName(any());
    }

    @Test
    void rename_rejectsNameAlreadyUsedByAVpnPeer() {
        // #284: renaming a LAN server onto a VPN peer's name collides across machines too.
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.1.50", false, null)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        assertThatThrownBy(() -> service.rename("nas", "apalveien5"))
            .isInstanceOf(ConflictException.class);
        verify(forPersistingLanServers, never()).save(any());
        verify(forPersistingLanServers, never()).deleteByName(any());
    }

    @Test
    void rename_isNoOpWhenNameUnchanged() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", false, null)));

        service.rename("nas", "nas");

        verify(forPersistingLanServers, never()).save(any());
        verify(forPersistingLanServers, never()).deleteByName(any());
    }

    @Test
    void rename_rejectsBlankNewName() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", false, null)));

        assertThatThrownBy(() -> service.rename("nas", "  "))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPersistingLanServers, never()).save(any());
        verify(forPersistingLanServers, never()).deleteByName(any());
    }

    // --- description (#54) ---

    @Test
    void register_withDescription_persistsItOnTheLanServer() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("nas", "192.168.3.50", true, 2375, "Synology NAS");

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().description()).isEqualTo("Synology NAS");
    }

    @Test
    void updateDescription_savesLanServerWithNewDescription() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", true, 2375)));

        service.updateDescription("nas", "Synology in the closet");

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("nas");
        assertThat(captor.getValue().description()).isEqualTo("Synology in the closet");
        assertThat(captor.getValue().lanAddress()).isEqualTo("192.168.1.50");
    }

    @Test
    void updateDescription_throwsWhenLanServerNotFound() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateDescription("ghost", "anything"))
            .isInstanceOf(NotFoundException.class);
        verify(forPersistingLanServers, never()).save(any());
    }

    // --- register with optional device-category override ---

    @Test
    void register_withDeviceCategoryOverride_persistsIt() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("box", "192.168.3.50", true, 2375, null, net.vaier.domain.DeviceCategory.NAS);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
    }

    @Test
    void register_withoutDeviceCategoryOverride_persistsNullOverride() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("box", "192.168.3.50", true, 2375);

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().deviceCategory()).isNull();
    }

    // --- updateDeviceCategory ---

    @Test
    void updateDeviceCategory_savesOverrideKeepingEverythingElse() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", true, 2375, "desc")));

        service.updateDeviceCategory("nas", "NAS");

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
        assertThat(captor.getValue().description()).isEqualTo("desc");
        assertThat(captor.getValue().lanAddress()).isEqualTo("192.168.1.50");
    }

    @Test
    void updateDeviceCategory_blankClearsOverride() {
        when(forPersistingLanServers.getAll())
            .thenReturn(List.of(new LanServer("nas", "192.168.1.50", false, null, null,
                net.vaier.domain.DeviceCategory.NAS)));

        service.updateDeviceCategory("nas", "  ");

        ArgumentCaptor<LanServer> captor = ArgumentCaptor.forClass(LanServer.class);
        verify(forPersistingLanServers).save(captor.capture());
        assertThat(captor.getValue().deviceCategory()).isNull();
    }

    @Test
    void updateDeviceCategory_rejectsInvalidValueWithoutSaving() {
        assertThatThrownBy(() -> service.updateDeviceCategory("nas", "BANANA"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forPersistingLanServers, never()).save(any());
    }

    @Test
    void updateDeviceCategory_throwsWhenLanServerNotFound() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateDeviceCategory("ghost", "NAS"))
            .isInstanceOf(NotFoundException.class);
        verify(forPersistingLanServers, never()).save(any());
    }

    // --- generateSetupScript (#249) — orchestration only; the decision matrix is in
    //     LanServerSetupScriptTest.forHost_* (domain). These verify the service reads the right
    //     ports and passes the configured vpnSubnet through. ---

    @Test
    void generateSetupScript_relayAnchored_passesPortDataAndVpnSubnetToDomain() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nuc02", "192.168.3.50", false, null, null)));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.9", "[Interface]",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.121", null)));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));

        String s = service.generateSetupScript("nuc02").orElseThrow();

        assertThat(s).contains("ip route replace 172.31.16.0/20 via 192.168.3.121"); // server LAN CIDR
        assertThat(s).contains("ip route replace 10.13.13.0/24 via 192.168.3.121");  // the vpnSubnet
    }

    @Test
    void generateSetupScript_unknownServer_empty() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThat(service.generateSetupScript("ghost")).isEmpty();
    }
}
