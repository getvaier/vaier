package net.vaier.rest;

import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.NotifyAdminsOfBackupServerDownUseCase;
import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServerWatcherTest {

    GetBackupServersUseCase servers;
    ForProbingTcp probe;
    NotifyAdminsOfBackupServerDownUseCase notifier;
    BackupServerWatcher watcher;

    private BackupServer server(String name) {
        return new BackupServer(name, "NAS", "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @BeforeEach
    void setUp() {
        servers = mock(GetBackupServersUseCase.class);
        probe = mock(ForProbingTcp.class);
        notifier = mock(NotifyAdminsOfBackupServerDownUseCase.class);
        watcher = new BackupServerWatcher(servers, probe, notifier);
    }

    @Test
    void connectedServer_neverAlerts() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.CONNECTED);

        watcher.checkBackupServers();
        watcher.checkBackupServers();

        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any());
        verify(notifier, never()).notifyAdminsOfBackupServerRecovered(any());
    }

    @Test
    void twoRefusedProbes_sendExactlyOneDownAlert_withContainerWording() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);

        watcher.checkBackupServers(); // strike 1, no page
        watcher.checkBackupServers(); // strike 2, crosses to down
        watcher.checkBackupServers(); // steady down, no re-page

        verify(notifier, times(1)).notifyAdminsOfBackupServerDown(any(BackupServer.class), eq(ProbeResult.REFUSED));
    }

    @Test
    void singleRefusedProbe_doesNotAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);

        watcher.checkBackupServers(); // one blip only

        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any());
    }

    @Test
    void recoveryAfterDown_sendsExactlyOneRecoveryAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt()))
            .thenReturn(ProbeResult.REFUSED, ProbeResult.REFUSED, ProbeResult.CONNECTED, ProbeResult.CONNECTED);

        watcher.checkBackupServers(); // strike 1
        watcher.checkBackupServers(); // down
        watcher.checkBackupServers(); // recovery
        watcher.checkBackupServers(); // steady healthy, quiet

        verify(notifier, times(1)).notifyAdminsOfBackupServerDown(any(), eq(ProbeResult.REFUSED));
        verify(notifier, times(1)).notifyAdminsOfBackupServerRecovered(any(BackupServer.class));
    }

    @Test
    void unreachableProbe_carriesUnreachableCauseIntoTheAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.UNREACHABLE);

        watcher.checkBackupServers();
        watcher.checkBackupServers();

        verify(notifier).notifyAdminsOfBackupServerDown(any(), eq(ProbeResult.UNREACHABLE));
    }

    @Test
    void notifierThrowing_doesNotBreakTheSweep() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);
        doThrow(new RuntimeException("smtp down"))
            .when(notifier).notifyAdminsOfBackupServerDown(any(), any());

        watcher.checkBackupServers();
        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkBackupServers())
            .doesNotThrowAnyException();
    }

    @Test
    void probeThrowing_doesNotBreakTheSweep() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(any(), anyInt(), anyInt())).thenThrow(new RuntimeException("boom"));

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkBackupServers())
            .doesNotThrowAnyException();
        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any());
    }

    @Test
    void noServers_noProbes() {
        when(servers.getBackupServers()).thenReturn(List.of());

        watcher.checkBackupServers();

        verify(probe, never()).probe(any(), anyInt(), anyInt());
    }
}
