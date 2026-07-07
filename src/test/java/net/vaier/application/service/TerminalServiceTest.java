package net.vaier.application.service;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredential;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForOpeningSshSessions;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
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
class TerminalServiceTest {

    @Mock ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    @Mock ForOpeningSshSessions forOpeningSshSessions;
    @Mock ForRunningSshCommands forRunningSshCommands;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock SshOutputListener onOutput;
    @Mock SshSession sshSession;

    @InjectMocks TerminalService service;

    private HostCredential passwordCred(String machine) {
        return new HostCredential(machine, "root", AuthMethod.PASSWORD, "pw", null, false);
    }

    // --- credential vault (slice 1, unchanged) ---

    @Test
    void saveHostCredential_persistsViaPort() {
        HostCredential credential = new HostCredential("nas", "admin", AuthMethod.PASSWORD, "s3cret", null, false);
        service.saveHostCredential(credential);
        verify(forPersistingHostCredentials).save(credential);
    }

    @Test
    void getHostCredential_returnsRedactedView_neverSecretBytes() {
        when(forPersistingHostCredentials.getByMachine("nas")).thenReturn(Optional.of(
            new HostCredential("nas", "admin", AuthMethod.PRIVATE_KEY, "-----BEGIN KEY-----", "keypass", false)));

        Optional<HostCredentialView> view = service.getHostCredential("nas");

        assertThat(view).contains(new HostCredentialView("nas", "admin", AuthMethod.PRIVATE_KEY, true));
    }

    @Test
    void deleteHostCredential_deletesViaPort() {
        service.deleteHostCredential("nas");
        verify(forPersistingHostCredentials).deleteByMachine("nas");
    }

    // --- terminal (slice 2) ---

    @Test
    void openTerminal_peer_resolvesTunnelIp_opensSession_pinsOnFirstUse() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.of(passwordCred("nuc")));
        when(forTrackingHostKeys.getFingerprint("nuc")).thenReturn(Optional.empty());
        when(sshSession.hostKeyFingerprint()).thenReturn("SHA256:fresh");
        when(forOpeningSshSessions.open(any(), any())).thenReturn(sshSession);

        SshSession result = service.openTerminal("nuc", onOutput);

        assertThat(result).isSameAs(sshSession);
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forOpeningSshSessions).open(target.capture(), any());
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");   // tunnel IP
        assertThat(target.getValue().pinnedFingerprint()).isNull();
        verify(forTrackingHostKeys).pin("nuc", "SHA256:fresh");        // TOFU pin on first use
    }

    @Test
    void openTerminal_lanServer_resolvesLanAddress() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of(
            new LanServer("nas", "192.168.3.50", true, 2375)));
        when(forPersistingHostCredentials.getByMachine("nas")).thenReturn(Optional.of(passwordCred("nas")));
        when(forTrackingHostKeys.getFingerprint("nas")).thenReturn(Optional.of("SHA256:pinned"));
        when(forOpeningSshSessions.open(any(), any())).thenReturn(sshSession);

        service.openTerminal("nas", onOutput);

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forOpeningSshSessions).open(target.capture(), any());
        assertThat(target.getValue().host()).isEqualTo("192.168.3.50");
        assertThat(target.getValue().pinnedFingerprint()).isEqualTo("SHA256:pinned");
        verify(forTrackingHostKeys, never()).pin(any(), any());   // already pinned → no re-pin
    }

    @Test
    void openTerminal_vaierServer_resolvesHostAddress() {
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        lenient().when(forPersistingLanServers.getAll()).thenReturn(List.of());
        when(forResolvingVaierServerSshAddress.resolve()).thenReturn("172.17.0.1");
        when(forPersistingHostCredentials.getByMachine(LanAnchor.VAIER_SERVER_NAME))
            .thenReturn(Optional.of(passwordCred(LanAnchor.VAIER_SERVER_NAME)));
        when(forTrackingHostKeys.getFingerprint(LanAnchor.VAIER_SERVER_NAME)).thenReturn(Optional.empty());
        when(sshSession.hostKeyFingerprint()).thenReturn("SHA256:host");
        when(forOpeningSshSessions.open(any(), any())).thenReturn(sshSession);

        service.openTerminal(LanAnchor.VAIER_SERVER_NAME, onOutput);

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forOpeningSshSessions).open(target.capture(), any());
        assertThat(target.getValue().host()).isEqualTo("172.17.0.1");
    }

    @Test
    void openTerminal_noCredential_throwsNoHostCredential_andDoesNotOpen() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openTerminal("nuc", onOutput))
            .isInstanceOf(NoHostCredentialException.class);
        verify(forOpeningSshSessions, never()).open(any(), any());
    }

    @Test
    void openTerminal_unknownMachine_throwsNotFound() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.openTerminal("ghost", onOutput))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void clearHostKey_clearsViaPort() {
        service.clearHostKey("nas");
        verify(forTrackingHostKeys).clear("nas");
    }

    // --- remote command (slice 3 keystone reuse) ---

    @Test
    void run_resolvesTarget_fromSameAddressCredentialAndPinLogic() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.of(passwordCred("nuc")));
        when(forTrackingHostKeys.getFingerprint("nuc")).thenReturn(Optional.of("SHA256:pinned"));
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, "hello", "", false, "SHA256:pinned"));

        CommandResult result = service.run("nuc", "echo hello");

        assertThat(result.stdout()).isEqualTo("hello");
        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forRunningSshCommands).run(target.capture(), org.mockito.ArgumentMatchers.eq("echo hello"));
        assertThat(target.getValue().host()).isEqualTo("10.13.13.9");
        assertThat(target.getValue().pinnedFingerprint()).isEqualTo("SHA256:pinned");
        verify(forTrackingHostKeys, never()).pin(any(), any()); // already pinned → no re-pin
    }

    @Test
    void run_firstUseUnpinnedHost_pinsPresentedFingerprint() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.of(passwordCred("nuc")));
        when(forTrackingHostKeys.getFingerprint("nuc")).thenReturn(Optional.empty());
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, "ok", "", false, "SHA256:fresh"));

        service.run("nuc", "echo ok");

        ArgumentCaptor<SshTarget> target = ArgumentCaptor.forClass(SshTarget.class);
        verify(forRunningSshCommands).run(target.capture(), any());
        assertThat(target.getValue().pinnedFingerprint()).isNull();
        verify(forTrackingHostKeys).pin("nuc", "SHA256:fresh"); // TOFU pin on first use
    }

    @Test
    void run_noCredential_throwsNoHostCredential_andDoesNotRun() {
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nuc", "nuc", "10.13.13.9", "", MachineType.UBUNTU_SERVER, null, null, null)));
        when(forPersistingHostCredentials.getByMachine("nuc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run("nuc", "echo hi"))
            .isInstanceOf(NoHostCredentialException.class);
        verify(forRunningSshCommands, never()).run(any(), any());
    }
}
