package net.vaier.rest;

import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.application.SweepImageUpdatesUseCase;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.UpdateAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageUpdateWatcherTest {

    SweepImageUpdatesUseCase sweep;
    NotifyAdminsOfUpdateAvailableUseCase notifier;
    ImageUpdateWatcher watcher;

    @BeforeEach
    void setUp() {
        sweep = mock(SweepImageUpdatesUseCase.class);
        notifier = mock(NotifyAdminsOfUpdateAvailableUseCase.class);
        // The tracker is injected since #57 slice 3 — the operator's own update check shares this memory, so
        // that a confirmed pull clears the alert state the watcher would otherwise keep believing.
        watcher = new ImageUpdateWatcher(sweep, notifier, new ImageUpdateTracker());
    }

    private static Map<String, UpdateAvailability> verdicts(Object... pairs) {
        Map<String, UpdateAvailability> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (UpdateAvailability) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void mailsAdminsOnceWhenAnImageBecomesOutOfDate() {
        when(sweep.sweepImageUpdates())
            .thenReturn(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        watcher.checkForImageUpdates();

        ArgumentCaptor<ImageUpdateRollup> rollup = ArgumentCaptor.forClass(ImageUpdateRollup.class);
        verify(notifier).notifyAdminsOfUpdateAvailable(rollup.capture());
        assertThat(rollup.getValue().images()).containsExactly("vaultwarden/server:latest");
    }

    @Test
    void threeImagesGoingStaleInOneSweepSendOneRollupNotThreeMails() {
        when(sweep.sweepImageUpdates()).thenReturn(verdicts(
            "a:1", UpdateAvailability.UPDATE_AVAILABLE,
            "b:1", UpdateAvailability.UPDATE_AVAILABLE,
            "c:1", UpdateAvailability.UPDATE_AVAILABLE));

        watcher.checkForImageUpdates();

        ArgumentCaptor<ImageUpdateRollup> rollup = ArgumentCaptor.forClass(ImageUpdateRollup.class);
        verify(notifier, times(1)).notifyAdminsOfUpdateAvailable(rollup.capture());
        assertThat(rollup.getValue().images()).containsExactly("a:1", "b:1", "c:1");
    }

    @Test
    void staysSilentWhileTheSameImageRemainsOutOfDate() {
        when(sweep.sweepImageUpdates())
            .thenReturn(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));

        watcher.checkForImageUpdates();
        watcher.checkForImageUpdates();
        watcher.checkForImageUpdates();

        verify(notifier, times(1)).notifyAdminsOfUpdateAvailable(any());
    }

    @Test
    void staysSilentWhenNothingIsOutOfDate() {
        when(sweep.sweepImageUpdates()).thenReturn(verdicts("a:1", UpdateAvailability.UP_TO_DATE));

        watcher.checkForImageUpdates();

        verify(notifier, never()).notifyAdminsOfUpdateAvailable(any());
    }

    @Test
    void staysSilentWhenEveryVerdictIsUnknown() {
        // Registry unreachable fleet-wide — say nothing rather than alarm.
        when(sweep.sweepImageUpdates()).thenReturn(verdicts("a:1", UpdateAvailability.UNKNOWN));

        watcher.checkForImageUpdates();

        verify(notifier, never()).notifyAdminsOfUpdateAvailable(any());
    }

    @Test
    void mailsOnlyTheNewlyStaleImageOnASecondSweep() {
        when(sweep.sweepImageUpdates())
            .thenReturn(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE))
            .thenReturn(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE,
                "b:1", UpdateAvailability.UPDATE_AVAILABLE));

        watcher.checkForImageUpdates();
        watcher.checkForImageUpdates();

        ArgumentCaptor<ImageUpdateRollup> rollup = ArgumentCaptor.forClass(ImageUpdateRollup.class);
        verify(notifier, times(2)).notifyAdminsOfUpdateAvailable(rollup.capture());
        assertThat(rollup.getAllValues().get(1).images()).containsExactly("b:1");
    }

    @Test
    void aFailedSweepIsSwallowedSoTheScheduleSurvivesIt() {
        when(sweep.sweepImageUpdates()).thenThrow(new RuntimeException("docker unreachable"));

        watcher.checkForImageUpdates();   // must not throw

        verify(notifier, never()).notifyAdminsOfUpdateAvailable(any());
    }

    @Test
    void aFailedMailIsSwallowedSoTheScheduleSurvivesIt() {
        when(sweep.sweepImageUpdates())
            .thenReturn(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
            .when(notifier).notifyAdminsOfUpdateAvailable(any());

        watcher.checkForImageUpdates();   // must not throw
    }
}
