package net.vaier.rest;

import net.vaier.application.GetMachinesUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.TestMachineIds;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.NotifyAdminsOfBackupServerDownUseCase;
import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    GetMachinesUseCase machines;
    ForProbingTcp probe;
    NotifyAdminsOfBackupServerDownUseCase notifier;
    BackupServerWatcher watcher;

    /** The machine hosting the borg server — its id is what the server record actually points at. */
    private Machine nas() {
        return new Machine(TestMachineIds.of("NAS"), "NAS", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.3.3", false, null, DeviceCategory.SERVER, null);
    }

    private BackupServer server(String name) {
        return new BackupServer(name, TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @BeforeEach
    void setUp() {
        servers = mock(GetBackupServersUseCase.class);
        probe = mock(ForProbingTcp.class);
        notifier = mock(NotifyAdminsOfBackupServerDownUseCase.class);
        machines = mock(GetMachinesUseCase.class);
        when(machines.getAllMachines()).thenReturn(List.of(nas()));
        watcher = new BackupServerWatcher(servers, machines, probe, notifier);
    }

    @Test
    void connectedServer_neverAlerts() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.CONNECTED);

        watcher.checkBackupServers();
        watcher.checkBackupServers();

        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any(), any());
        verify(notifier, never()).notifyAdminsOfBackupServerRecovered(any(), any());
    }

    @Test
    void twoRefusedProbes_sendExactlyOneDownAlert_withContainerWording() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);

        watcher.checkBackupServers(); // strike 1, no page
        watcher.checkBackupServers(); // strike 2, crosses to down
        watcher.checkBackupServers(); // steady down, no re-page

        verify(notifier, times(1)).notifyAdminsOfBackupServerDown(any(BackupServer.class), any(), eq(ProbeResult.REFUSED));
    }

    @Test
    void aBackupServerSwitchedOffOnPurpose_isMailedNeitherGoingNorComingBack() {
        when(machines.getAllMachines()).thenReturn(List.of(nas().toBuilder().switchedOffSince(Instant.EPOCH).build()));
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE, ProbeResult.UNREACHABLE, ProbeResult.CONNECTED);

        watcher.checkBackupServers();
        watcher.checkBackupServers();
        // By the time it answers again the mark has been cleared — the quiet going-down keeps it quiet.
        when(machines.getAllMachines()).thenReturn(List.of(nas()));
        watcher.checkBackupServers();

        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any(), any());
        verify(notifier, never()).notifyAdminsOfBackupServerRecovered(any(), any());
    }

    @Test
    void singleRefusedProbe_doesNotAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);

        watcher.checkBackupServers(); // one blip only

        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any(), any());
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

        verify(notifier, times(1)).notifyAdminsOfBackupServerDown(any(), any(), eq(ProbeResult.REFUSED));
        verify(notifier, times(1)).notifyAdminsOfBackupServerRecovered(any(BackupServer.class), any());
    }

    @Test
    void unreachableProbe_carriesUnreachableCauseIntoTheAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.UNREACHABLE);

        watcher.checkBackupServers();
        watcher.checkBackupServers();

        verify(notifier).notifyAdminsOfBackupServerDown(any(), any(), eq(ProbeResult.UNREACHABLE));
    }

    @Test
    void notifierThrowing_doesNotBreakTheSweep() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);
        doThrow(new RuntimeException("smtp down"))
            .when(notifier).notifyAdminsOfBackupServerDown(any(), any(), any());

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
        verify(notifier, never()).notifyAdminsOfBackupServerDown(any(), any(), any());
    }

    @Test
    void noServers_noProbes() {
        when(servers.getBackupServers()).thenReturn(List.of());

        watcher.checkBackupServers();

        verify(probe, never()).probe(any(), anyInt(), anyInt());
    }

    // --- resolving what to CALL the machine must never be able to break the sweep ----------------------

    /**
     * The regression this guards. The machine registry reads WireGuard by shelling into a container, so it
     * throws whenever that container is restarting — an ordinary state of a fleet. Resolving a machine's
     * display name is a cosmetic step for an email; it must never take the sweep down with it, and above all
     * it must never lose the down-alert it was only ever there to decorate.
     */
    @Test
    void aMachineRegistryThatThrows_neitherBreaksTheSweepNorSwallowsTheAlert() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.REFUSED);
        when(machines.getAllMachines()).thenThrow(new RuntimeException("wireguard container restarting"));

        org.assertj.core.api.Assertions.assertThatCode(() -> {
            watcher.checkBackupServers();   // strike 1
            watcher.checkBackupServers();   // strike 2, crosses to down
        }).doesNotThrowAnyException();

        // The alert still goes out — naming the machine as unresolvable beats not alerting at all.
        verify(notifier).notifyAdminsOfBackupServerDown(any(), any(), eq(ProbeResult.REFUSED));
    }

    /**
     * And it is not paid at all on a quiet sweep. A healthy server crosses no boundary, so there is no email
     * to name a machine for — reading the whole machine registry (a container exec) every five minutes to
     * decorate an alert that is not being sent is work for nothing.
     */
    @Test
    void aQuietSweep_neverReadsTheMachineRegistry() {
        when(servers.getBackupServers()).thenReturn(List.of(server("nas-borg")));
        when(probe.probe(eq("192.168.3.3"), eq(8022), anyInt())).thenReturn(ProbeResult.CONNECTED);

        watcher.checkBackupServers();
        watcher.checkBackupServers();

        verify(machines, never()).getAllMachines();
    }
}
