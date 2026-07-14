package net.vaier.rest;

import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.DiskFillForecast;
import net.vaier.domain.DiskFillForecastCleared;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.RemoteDiskUsage;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    GetDiskWatchesUseCase diskWatches;
    ConfigResolver configResolver;
    SteppableClock clock;
    RemoteDiskWatcher watcher;

    /** A clock the test advances by hand, so a rising df series can be fed across deterministic polls. */
    static final class SteppableClock extends Clock {
        private Instant now = Instant.parse("2026-07-08T00:00:00Z");

        void advance(Duration by) { now = now.plus(by); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach
    void setUp() {
        machines = mock(GetMachinesUseCase.class);
        credentials = mock(GetHostCredentialUseCase.class);
        runner = mock(RunRemoteCommandUseCase.class);
        notifier = mock(NotifyAdminsOfRemoteDiskPressureUseCase.class);
        forecastNotifier = mock(NotifyAdminsOfDiskFillForecastUseCase.class);
        diskWatches = mock(GetDiskWatchesUseCase.class);
        configResolver = mock(ConfigResolver.class);
        clock = new SteppableClock();
        // Nothing configured: every filesystem is watched at the global threshold (#325).
        lenient().when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of()));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        watcher = new RemoteDiskWatcher(machines, credentials, runner, notifier, forecastNotifier,
            diskWatches, configResolver, clock);
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
    void risingSeriesAcrossPolls_notifiesForecastOnce_whileStillBelowLevel() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // A steady 1%/h climb, well below the 85 level: 74 → 75 → 76 (runway 24h) → 77 (runway 23h, crosses).
        int[] series = {74, 75, 76, 77, 78};
        for (int used : series) {
            when(runner.run(eq("nas"), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, times(1)).notifyAdminsOfDiskFillForecast(any(DiskFillForecast.class));
        // Never a level alert — the disk stayed below the disk-pressure threshold throughout.
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void drainingBelowThreshold_afterWarning_sendsAllClearWithCurrentPercent() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Climb below the level threshold until the early warning fires...
        for (int used : new int[]{74, 75, 76, 77}) {
            when(runner.run(eq("nas"), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }
        // ...then space is freed (sharp drop) while still below threshold → genuine recovery.
        when(runner.run(eq("nas"), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage();

        org.mockito.ArgumentCaptor<DiskFillForecastCleared> cleared =
            org.mockito.ArgumentCaptor.forClass(DiskFillForecastCleared.class);
        verify(forecastNotifier).notifyAdminsOfDiskFillForecastCleared(cleared.capture());
        org.assertj.core.api.Assertions.assertThat(cleared.getValue().currentPercent()).isEqualTo(50);
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void climbingPastThreshold_afterWarning_suppressesForecastClear_onlyPressureAlerts() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Climb below threshold until the early warning fires...
        for (int used : new int[]{74, 75, 76, 77}) {
            when(runner.run(eq("nas"), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }
        // ...then it crosses the level threshold → the disk-pressure alert speaks; the forecast clear
        // must be suppressed so admins aren't double-paged at the same poll.
        when(runner.run(eq("nas"), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage();

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecastCleared(any());
    }

    @Test
    void failedPoll_recordsNoSample_soNoForecastFromBadReadings() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Every poll fails; a failed df must record no sample, so a forecast can never form.
        when(runner.run(eq("nas"), any()))
            .thenReturn(new CommandResult(-1, "", "", true, "SHA256:abc"));
        for (int i = 0; i < 5; i++) {
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecast(any());
    }

    @Test
    void usesTheDomainsDfCommand_whichReadsEveryFilesystem_notJustRoot() {
        // #325: it used to be `df -P /`. That is the bug — the filesystem that matters is very often not
        // the root one. The command lives on RemoteDiskUsage, next to the parser that reads it.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        lenient().when(runner.run(eq("nas"), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        verify(runner).run(eq("nas"), eq("df -P"));
    }

    // --- every watched filesystem, not just the root one (#325) -----------------------------------------
    //
    // The NAS is the whole reason this issue exists. Its / is the 2.3 GB DSM system partition — 88% by
    // design, and it never moves — so Vaier alerted about a partition the operator could not act on, while
    // /volume1 (11.6 TB, every borg backup) was invisible and could have filled to 100% in silence.

    /** The real `df -P` from the NAS, with /volume1 driven to `volume1Percent`. / stays at its usual 88%. */
    private CommandResult nasDf(int volume1Percent) {
        return nasDf(88, volume1Percent);
    }

    /** The real `df -P` from the NAS, with / and /volume1 each driven where the test needs them. */
    private CommandResult nasDf(int rootPercent, int volume1Percent) {
        long size = 11614435576L;
        long used = size / 100 * volume1Percent;
        String out = "Filesystem             1024-blocks       Used  Available Capacity Mounted on\n"
            + "/dev/md0                   2385528    1988940     277804      " + rootPercent + "% /\n"
            + "tmpfs                      2021044       1988    2019056       1% /tmp\n"
            + "/dev/mapper/cachedev_0   115404288     512932  114875740       1% /volume2\n"
            + "/dev/mapper/cachedev_1 " + size + " " + used + " " + (size - used) + " "
            + volume1Percent + "% /volume1\n"
            + "none                   " + size + " " + used + " " + (size - used) + " "
            + volume1Percent + "% /volume1/@docker/aufs/mnt/b5720e8\n";
        return new CommandResult(0, out, "", false, "SHA256:abc");
    }

    @Test
    void theVolumeThatUsedToBeInvisible_nowAlerts() {
        // /volume1 filling is precisely what Vaier could never say a word about.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", true, 95))));      // the DSM system partition, given its own threshold

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();                   // baseline: nothing breaches
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();                   // /volume1 crosses

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getValue().mountPoint()).isEqualTo("/volume1");
        assertThat(alerted.getValue().usedPercent()).isEqualTo(91);
    }

    @Test
    void theAlertNamesTheMountAndItsSize_soTheNumberMeansSomething() {
        // "NAS is at 88%" told the operator nothing they could act on — they checked DSM, found the disk
        // nowhere near full, and rightly stopped trusting Vaier.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", true, 95))));

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getValue().pressureSubject())
            .contains("NAS").contains("/volume1").contains("91%").contains("TiB");
    }

    @Test
    void aFilesystemWithItsOwnThreshold_isJudgedAgainstIt_notTheGlobalOne() {
        // The NAS's / at 88% would page forever at the global 85%. Its own 95% threshold keeps it quiet —
        // and still watched, so a genuinely full system partition still speaks.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", true, 95))));

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void aMutedFilesystem_neverAlerts_howeverFull() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", false, null),
            new DiskWatch("NAS", "/volume1", false, null),
            new DiskWatch("NAS", "/volume2", false, null))));

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(20));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(100));
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void anUnconfiguredFilesystem_isWatched_neverSilentlyIgnored() {
        // The default is watched, and that is not an accident: the failure being fixed IS silence about the
        // disk that matters, so a mount nobody has configured nags rather than hides.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        // No watches at all — /volume1 has never been configured. It is watched anyway, at the global 85%.
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();                   // baseline
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();                   // /volume1 crosses

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getValue().mountPoint()).isEqualTo("/volume1");
    }

    @Test
    void twoFilesystemsOnOneMachine_crossIndependently() {
        // The pressure tracker is keyed on machine AND mount now. Keyed on machine alone, /volume1 crossing
        // would be swallowed by / already being above — the second disk would never be heard.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(50, 39));
        watcher.checkRemoteDiskUsage();     // baseline: both below
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(90, 39));
        watcher.checkRemoteDiskUsage();     // / crosses above → alerts
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(90, 91));
        watcher.checkRemoteDiskUsage();     // /volume1 crosses above → must ALSO alert

        // Keyed on the machine alone, the tracker would already be "in pressure" from / and would swallow
        // /volume1's crossing as "no change" — the disk that matters would never be heard.
        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(2)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), anyInt());
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/volume1");
    }

    @Test
    void theForecastIsKeptPerFilesystem_notPerMachine() {
        // The forecast tracker is keyed on machine AND mount too. /volume1 climbing must forecast on its own
        // trend — a flat / on the same machine must not dilute or mask it.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", true, 95))));      // keep / quiet so only /volume1 can speak

        // /volume1 climbs 1%/h toward full while / sits at its usual 88%.
        for (int used : new int[]{74, 75, 76, 77, 78}) {
            when(runner.run(eq("NAS"), any())).thenReturn(nasDf(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        ArgumentCaptor<DiskFillForecast> forecast = ArgumentCaptor.forClass(DiskFillForecast.class);
        verify(forecastNotifier, times(1)).notifyAdminsOfDiskFillForecast(forecast.capture());
        assertThat(forecast.getValue().mountPoint()).isEqualTo("/volume1");
        assertThat(forecast.getValue().machineName()).isEqualTo("NAS");
    }

    @Test
    void aMutedFilesystem_isNotForecastEither() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch("NAS", "/", false, null),
            new DiskWatch("NAS", "/volume1", false, null),
            new DiskWatch("NAS", "/volume2", false, null))));

        for (int used : new int[]{74, 75, 76, 77, 78}) {
            when(runner.run(eq("NAS"), any())).thenReturn(nasDf(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecast(any());
    }

    @Test
    void thePseudoFilesystemsAndTheAufsAliases_areNeverAlertedOn() {
        // The NAS's df carries eight `none` rows, every one an alias of /volume1. Alerting on them would
        // page the operator nine times for one volume.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");

        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(50, 39));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq("NAS"), any())).thenReturn(nasDf(50, 91));
        watcher.checkRemoteDiskUsage();

        // /volume1 crosses once. Its eight aufs aliases carry the identical reading — if they were real
        // filesystems the operator would be paged nine times for one volume.
        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(1)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), anyInt());
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/volume1")
            .doesNotContain("/tmp")
            .noneMatch(mount -> mount.startsWith("/volume1/@docker"));
    }
}
