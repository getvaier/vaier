package net.vaier.rest;

import net.vaier.application.GetPeerConfigUseCase;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.application.ResolveVpnPeerNameUseCase;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.MachineType;
import net.vaier.domain.VpnClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeerConnectivityWatcherTest {

    GetVpnClientsUseCase vpnClients;
    ResolveVpnPeerNameUseCase peerNameResolver;
    GetPeerConfigUseCase peerConfigs;
    NotifyAdminsOfPeerTransitionUseCase notifier;
    PeerConnectivityWatcher watcher;

    @BeforeEach
    void setUp() {
        vpnClients = mock(GetVpnClientsUseCase.class);
        peerNameResolver = mock(ResolveVpnPeerNameUseCase.class);
        peerConfigs = mock(GetPeerConfigUseCase.class);
        notifier = mock(NotifyAdminsOfPeerTransitionUseCase.class);
        watcher = new PeerConnectivityWatcher(vpnClients, peerNameResolver, peerConfigs, notifier);
    }

    private VpnClient client(String allowedIps, String latestHandshake) {
        return new VpnClient("pk-" + allowedIps, allowedIps, "1.2.3.4", "51820", latestHandshake, "0", "0");
    }

    private GetPeerConfigUseCase.PeerConfigResult configResult(String name, String ip, MachineType type) {
        return new GetPeerConfigUseCase.PeerConfigResult(name, ip, "", type, null, null);
    }

    private static String recent() {
        return String.valueOf(System.currentTimeMillis() / 1000 - 30);
    }

    @Test
    void firstTick_doesNotNotify_baselineOnly() {
        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.2/32", recent())));
        when(peerNameResolver.resolvePeerNameByIp("10.0.0.2")).thenReturn("server-a");
        when(peerConfigs.getPeerConfigByIp("10.0.0.2"))
                .thenReturn(Optional.of(configResult("server-a", "10.0.0.2", MachineType.UBUNTU_SERVER)));

        watcher.checkConnectivity();

        verify(notifier, never()).notifyAdmins(any());
    }

    @Test
    void serverPeerTransitionsToDisconnected_notifiesAdmins() {
        when(peerNameResolver.resolvePeerNameByIp("10.0.0.2")).thenReturn("server-a");
        when(peerConfigs.getPeerConfigByIp("10.0.0.2"))
                .thenReturn(Optional.of(configResult("server-a", "10.0.0.2", MachineType.UBUNTU_SERVER)));

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.2/32", recent())));
        watcher.checkConnectivity();

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.2/32", "0")));
        watcher.checkConnectivity();

        ArgumentCaptor<PeerSnapshot> captor = ArgumentCaptor.forClass(PeerSnapshot.class);
        verify(notifier).notifyAdmins(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("server-a");
        assertThat(captor.getValue().connected()).isFalse();
        assertThat(captor.getValue().peerType()).isEqualTo(MachineType.UBUNTU_SERVER);
    }

    @Test
    void clientPeerTransition_isIgnored() {
        when(peerNameResolver.resolvePeerNameByIp("10.0.0.2")).thenReturn("phone");
        when(peerConfigs.getPeerConfigByIp("10.0.0.2"))
                .thenReturn(Optional.of(configResult("phone", "10.0.0.2", MachineType.MOBILE_CLIENT)));

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.2/32", recent())));
        watcher.checkConnectivity();

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.2/32", "0")));
        watcher.checkConnectivity();

        verify(notifier, never()).notifyAdmins(any());
    }

    @Test
    void windowsServerTransition_isNotified() {
        when(peerNameResolver.resolvePeerNameByIp("10.0.0.5")).thenReturn("win-server");
        when(peerConfigs.getPeerConfigByIp("10.0.0.5"))
                .thenReturn(Optional.of(configResult("win-server", "10.0.0.5", MachineType.WINDOWS_SERVER)));

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.5/32", "0")));
        watcher.checkConnectivity();

        when(vpnClients.getClients()).thenReturn(List.of(client("10.0.0.5/32", recent())));
        watcher.checkConnectivity();

        ArgumentCaptor<PeerSnapshot> captor = ArgumentCaptor.forClass(PeerSnapshot.class);
        verify(notifier).notifyAdmins(captor.capture());
        assertThat(captor.getValue().connected()).isTrue();
        assertThat(captor.getValue().peerType()).isEqualTo(MachineType.WINDOWS_SERVER);
    }

    @Test
    void exceptionsFromClientFetch_doNotPropagate() {
        when(vpnClients.getClients()).thenThrow(new RuntimeException("wg down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkConnectivity())
                .doesNotThrowAnyException();
    }
}
