package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The one place a machine name becomes a connectable {@link SshTarget}: address by machine kind,
 * credential from the vault, previously pinned host key. Every SSH consumer (terminal, Explorer) goes
 * through it, so there is exactly one copy of the trust-on-first-use lookup.
 */
@ExtendWith(MockitoExtension.class)
class MachineSshTargetAdapterTest {

    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    @Mock ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock ForTrackingHostKeys forTrackingHostKeys;

    @InjectMocks MachineSshTargetAdapter adapter;

    private HostCredential passwordCred(String machine) {
        return new HostCredential(machine, "root", AuthMethod.PASSWORD, "pw", null, false);
    }

    @Test
    void peer_resolvesToTheTunnelIp_withVaultCredentialAndPinnedKey() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.of(passwordCred("nuc")));
        when(forTrackingHostKeys.getFingerprint("nuc")).thenReturn(Optional.of("SHA256:pinned"));

        SshTarget target = adapter.resolve("nuc");

        assertThat(target.host()).isEqualTo("10.13.13.9");
        assertThat(target.port()).isEqualTo(SshTarget.DEFAULT_PORT);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(target.pinnedFingerprint()).isEqualTo("SHA256:pinned");
    }

    @Test
    void lanServer_resolvesToTheLanAddress() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of(new LanServer("nas", "192.168.3.50", true, 2375)));
        when(forPersistingHostCredentials.getByMachine("nas")).thenReturn(Optional.of(passwordCred("nas")));
        when(forTrackingHostKeys.getFingerprint("nas")).thenReturn(Optional.empty());

        SshTarget target = adapter.resolve("nas");

        assertThat(target.host()).isEqualTo("192.168.3.50");
        assertThat(target.pinnedFingerprint()).isNull();   // never pinned yet — first use will pin it
    }

    @Test
    void vaierServer_resolvesToTheResolvedHostAddress() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        when(forResolvingVaierServerSshAddress.resolve()).thenReturn("172.17.0.1");
        when(forPersistingHostCredentials.getByMachine(LanAnchor.VAIER_SERVER_NAME))
            .thenReturn(Optional.of(passwordCred(LanAnchor.VAIER_SERVER_NAME)));
        when(forTrackingHostKeys.getFingerprint(LanAnchor.VAIER_SERVER_NAME)).thenReturn(Optional.empty());

        SshTarget target = adapter.resolve(LanAnchor.VAIER_SERVER_NAME);

        assertThat(target.host()).isEqualTo("172.17.0.1");
    }

    @Test
    void unknownMachine_throwsNotFound() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> adapter.resolve("ghost"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    void machineWithoutAVaultCredential_throwsNoHostCredential() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.resolve("nuc"))
            .isInstanceOf(NoHostCredentialException.class);
    }
}
