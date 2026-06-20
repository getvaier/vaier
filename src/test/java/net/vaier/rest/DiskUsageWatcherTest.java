package net.vaier.rest;

import net.vaier.application.GetHostDiskUsageUseCase;
import net.vaier.application.NotifyAdminsOfDiskPressureUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DiskUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiskUsageWatcherTest {

    GetHostDiskUsageUseCase diskUsage;
    NotifyAdminsOfDiskPressureUseCase notifier;
    ConfigResolver configResolver;
    DiskUsageWatcher watcher;

    @BeforeEach
    void setUp() {
        diskUsage = mock(GetHostDiskUsageUseCase.class);
        notifier = mock(NotifyAdminsOfDiskPressureUseCase.class);
        configResolver = mock(ConfigResolver.class);
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        watcher = new DiskUsageWatcher(diskUsage, notifier, configResolver);
    }

    private DiskUsage usagePercent(int usedPercent) {
        return new DiskUsage("/host", 100L, 100L - usedPercent);
    }

    @Test
    void firstTick_doesNotNotify_baselineOnly() {
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(90));

        watcher.checkDiskUsage();

        verify(notifier, never()).notifyAdminsOfDiskPressure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void crossingAboveThreshold_alertsAdmins() {
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(50));
        watcher.checkDiskUsage(); // baseline below

        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(90));
        watcher.checkDiskUsage(); // crosses above

        verify(notifier).notifyAdminsOfDiskPressure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(85));
        verify(notifier, never()).notifyAdminsOfDiskRecovery(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void droppingBackBelowThreshold_sendsRecovery() {
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(90));
        watcher.checkDiskUsage(); // baseline above

        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(50));
        watcher.checkDiskUsage(); // crosses below

        verify(notifier).notifyAdminsOfDiskRecovery(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(85));
    }

    @Test
    void stayingAboveThreshold_doesNotReAlert() {
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(50));
        watcher.checkDiskUsage(); // baseline
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(90));
        watcher.checkDiskUsage(); // crosses above (1 alert)
        watcher.checkDiskUsage(); // still above, no new alert

        verify(notifier, org.mockito.Mockito.times(1)).notifyAdminsOfDiskPressure(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void usesConfiguredThresholdNotHardcoded() {
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(60);
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(50));
        watcher.checkDiskUsage(); // baseline: 50% below 60% threshold
        when(diskUsage.getHostDiskUsage()).thenReturn(usagePercent(65));
        watcher.checkDiskUsage(); // 65% > 60% -> crosses above

        verify(notifier).notifyAdminsOfDiskPressure(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(60));
    }

    @Test
    void exceptionsFromDiskRead_doNotPropagate() {
        when(diskUsage.getHostDiskUsage()).thenThrow(new RuntimeException("no mount"));

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkDiskUsage())
                .doesNotThrowAnyException();
    }
}
