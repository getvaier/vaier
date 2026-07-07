package net.vaier.rest;

import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.RemoteDiskUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteDiskWatcherTest {

    GetMachinesUseCase machines;
    GetHostCredentialUseCase credentials;
    RunRemoteCommandUseCase runner;
    NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    ConfigResolver configResolver;
    RemoteDiskWatcher watcher;

    @BeforeEach
    void setUp() {
        machines = mock(GetMachinesUseCase.class);
        credentials = mock(GetHostCredentialUseCase.class);
        runner = mock(RunRemoteCommandUseCase.class);
        notifier = mock(NotifyAdminsOfRemoteDiskPressureUseCase.class);
        configResolver = mock(ConfigResolver.class);
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        watcher = new RemoteDiskWatcher(machines, credentials, runner, notifier, configResolver);
    }

    /** An SSH-capable server-type machine (effectiveSshAccess() true by default). */
    private Machine sshMachine(String name) {
        return new Machine(name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private void hasCredential(String name) {
        when(credentials.getHostCredential(name)).thenReturn(
            Optional.of(new HostCredentialView(name, "root", AuthMethod.PASSWORD, true)));
    }

    private CommandResult df(int usedPercent) {
        String out = "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
            + "/dev/root 100 " + usedPercent + " " + (100 - usedPercent) + " " + usedPercent + "% /\n";
        return new CommandResult(0, out, "", false, "SHA256:abc");
    }

    @Test
    void crossingIntoPressure_alertsAdmins() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq("nas"), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage(); // baseline below

        when(runner.run(eq("nas"), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // crosses above

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
        verify(notifier, never()).notifyAdminsOfRemoteDiskRecovery(any(), anyInt());
    }

    @Test
    void stayingAboveThreshold_doesNotReAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq("nas"), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq("nas"), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // one alert
        watcher.checkRemoteDiskUsage(); // still above, no new alert

        verify(notifier, times(1)).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void droppingBackBelowThreshold_sendsRecovery() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq("nas"), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // baseline above
        when(runner.run(eq("nas"), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage(); // crosses below

        verify(notifier).notifyAdminsOfRemoteDiskRecovery(any(RemoteDiskUsage.class), eq(85));
    }

    @Test
    void machineWithoutCredential_isSkipped_neverRuns() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        when(credentials.getHostCredential("nas")).thenReturn(Optional.empty());

        watcher.checkRemoteDiskUsage();

        verify(runner, never()).run(any(), any());
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void machineWithSshAccessOff_isSkipped_neverRuns() {
        // sshAccessOverride = false forces effectiveSshAccess() false
        Machine off = new Machine("printer", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
            null, "192.168.1.111", false, null, DeviceCategory.SERVER, false);
        when(machines.getAllMachines()).thenReturn(List.of(off));

        watcher.checkRemoteDiskUsage();

        verify(credentials, never()).getHostCredential(any());
        verify(runner, never()).run(any(), any());
    }

    @Test
    void execFailure_timedOut_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq("nas"), any()))
            .thenReturn(new CommandResult(-1, "", "", true, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
        verify(notifier, never()).notifyAdminsOfRemoteDiskRecovery(any(), anyInt());
    }

    @Test
    void execFailure_nonZeroExit_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq("nas"), any()))
            .thenReturn(new CommandResult(127, "", "df: not found", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void execFailure_unparseableOutput_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq("nas"), any()))
            .thenReturn(new CommandResult(0, "totally not df output", "", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void runnerThrowing_doesNotPropagate_andSkipsMachine() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq("nas"), any())).thenThrow(new RuntimeException("unreachable"));

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkRemoteDiskUsage())
            .doesNotThrowAnyException();
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void usesTheConfiguredDfCommand_scopedToRoot() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        lenient().when(runner.run(eq("nas"), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        verify(runner).run(eq("nas"), eq("df -P /"));
    }
}
