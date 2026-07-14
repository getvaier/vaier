package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Where Vaier opens the SSH connection for a machine — a decision by machine kind, so it lives in the
 * domain and both the web terminal and the Explorer inherit the same answer.
 */
@ExtendWith(MockitoExtension.class)
class SshAddressTest {

    @Mock ForGettingPeerConfigurations peers;
    @Mock ForPersistingLanServers lanServers;
    @Mock ForResolvingVaierServerSshAddress vaierServer;

    private String addressOf(String machineName) {
        return SshAddress.of(machineName, peers, lanServers, vaierServer);
    }

    @Test
    void aVpnPeer_isReachedAtItsTunnelIp() {
        when(peers.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));

        assertThat(addressOf("nuc")).isEqualTo("10.13.13.9");
    }

    @Test
    void aLanServer_isReachedAtItsLanAddress() {
        lenient().when(peers.getAllPeerConfigs()).thenReturn(List.of());
        when(lanServers.getAll()).thenReturn(List.of(new LanServer("nas", "192.168.3.50", true, 2375)));

        assertThat(addressOf("nas")).isEqualTo("192.168.3.50");
    }

    @Test
    void theVaierServerHost_isReachedAtItsResolvedHostAddress() {
        // The Vaier host is neither a peer nor a LAN server, so its address cannot be read from config.
        when(vaierServer.resolve()).thenReturn("172.17.0.1");

        assertThat(addressOf(LanAnchor.VAIER_SERVER_NAME)).isEqualTo("172.17.0.1");
    }

    @Test
    void aPeerIsPreferredOverALanServerOfTheSameName() {
        when(peers.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nas", "nas", "10.13.13.4", "", MachineType.UBUNTU_SERVER, null, null, null)));

        assertThat(addressOf("nas")).isEqualTo("10.13.13.4");
    }

    @Test
    void aMachineThatIsNeither_doesNotExist() {
        when(peers.getAllPeerConfigs()).thenReturn(List.of());
        when(lanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> addressOf("ghost"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }
}
