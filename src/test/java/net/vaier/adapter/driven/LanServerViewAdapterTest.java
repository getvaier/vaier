package net.vaier.adapter.driven;

import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingServerLanCidr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerViewAdapterTest {

    @Mock private ForPersistingLanServers forPersistingLanServers;
    @Mock private ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock private ForResolvingServerLanCidr forResolvingServerLanCidr;

    @InjectMocks private LanServerViewAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());
    }

    private static PeerConfiguration relay(String name, String ip, String lanCidr) {
        return new PeerConfiguration(name, ip, "", MachineType.UBUNTU_SERVER, lanCidr, null);
    }

    @Test
    void getAll_resolvesRelayPeerNameForEachServer() {
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)
        ));
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "10.13.13.5", "192.168.3.0/24")
        ));

        List<LanServerView> views = adapter.getAll();

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

        List<LanServerView> views = adapter.getAll();

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

        List<LanServerView> views = adapter.getAll();

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

        assertThat(adapter.getAll().get(0).relayPeerName()).isEqualTo("apalveien5");
    }
}
