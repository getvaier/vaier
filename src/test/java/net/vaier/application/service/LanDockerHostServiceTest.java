package net.vaier.application.service;

import net.vaier.application.GetLanDockerHostsUseCase.LanDockerHostView;
import net.vaier.domain.LanDockerHost;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanDockerHosts;
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
class LanDockerHostServiceTest {

    @Mock private ForPersistingLanDockerHosts forPersistingLanDockerHosts;
    @Mock private ForGettingPeerConfigurations forGettingPeerConfigurations;

    @InjectMocks private LanDockerHostService service;

    private static PeerConfiguration relay(String name, String ip, String lanCidr) {
        return new PeerConfiguration(name, ip, "", PeerType.UBUNTU_SERVER, lanCidr, null);
    }

    // --- register ---

    @Test
    void register_hostIpInsideRelayLanCidr_persistsHost() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        service.register("nas", "192.168.3.50", 2375);

        ArgumentCaptor<LanDockerHost> captor = ArgumentCaptor.forClass(LanDockerHost.class);
        verify(forPersistingLanDockerHosts).save(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new LanDockerHost("nas", "192.168.3.50", 2375));
    }

    @Test
    void register_hostIpOutsideAllRelayLanCidrs_throwsAndDoesNotPersist() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        assertThatThrownBy(() -> service.register("nas", "10.99.99.99", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");
        verify(forPersistingLanDockerHosts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void register_invalidPort_throws() {
        assertThatThrownBy(() -> service.register("nas", "192.168.3.50", 70000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("port");
        verify(forPersistingLanDockerHosts, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void register_blankName_throws() {
        assertThatThrownBy(() -> service.register("", "192.168.3.50", 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void register_noRelayPeersExist_throws() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        assertThatThrownBy(() -> service.register("nas", "192.168.3.50", 2375))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- delete ---

    @Test
    void delete_callsAdapterWithName() {
        service.delete("nas");

        verify(forPersistingLanDockerHosts).deleteByName("nas");
    }

    // --- getAll ---

    @Test
    void getAll_resolvesRelayPeerNameForEachHost() {
        when(forPersistingLanDockerHosts.getAll()).thenReturn(List.of(
            new LanDockerHost("nas", "192.168.3.50", 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        List<LanDockerHostView> views = service.getAll();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).host()).isEqualTo(new LanDockerHost("nas", "192.168.3.50", 2375));
        assertThat(views.get(0).relayPeerName()).isEqualTo("apalveien5");
    }

    @Test
    void getAll_relayWasDeleted_returnsHostWithNullRelayName() {
        when(forPersistingLanDockerHosts.getAll()).thenReturn(List.of(
            new LanDockerHost("nas", "192.168.3.50", 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());

        List<LanDockerHostView> views = service.getAll();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).relayPeerName()).isNull();
    }
}
