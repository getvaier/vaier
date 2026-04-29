package net.vaier.application.service;

import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerServiceTest {

    @Mock private ForPersistingLanServers forPersistingLanServers;
    @Mock private ForGettingPeerConfigurations forGettingPeerConfigurations;

    @InjectMocks private LanServerService service;

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

    // --- delete ---

    @Test
    void delete_callsAdapterWithName() {
        service.delete("nas");

        verify(forPersistingLanServers).deleteByName("nas");
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
}
