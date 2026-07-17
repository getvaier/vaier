package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateTrackerTest {

    private static Map<String, UpdateAvailability> verdicts(Object... pairs) {
        Map<String, UpdateAvailability> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (UpdateAvailability) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void reportsAnImageThatIsAlreadyOutOfDateOnTheVeryFirstSweep() {
        // Deliberately NOT baseline-quiet, unlike the peer/disk trackers. The #57 incident was an image that
        // was *already* stale: if the first sweep after a restart stayed silent, the one case this feature
        // exists for would be the one case it never reports.
        ImageUpdateTracker tracker = new ImageUpdateTracker();

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("vaultwarden/server:latest");
    }

    @Test
    void staysSilentWhileTheSameImageRemainsOutOfDate() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .isEmpty();
    }

    @Test
    void reportsOnlyTheNewlyOutOfDateImagesInASweep() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE, "b:1", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts(
            "a:1", UpdateAvailability.UPDATE_AVAILABLE,
            "b:1", UpdateAvailability.UPDATE_AVAILABLE,
            "c:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("b:1", "c:1");
    }

    @Test
    void staysSilentWhenNothingChanged() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE))).isEmpty();
    }

    @Test
    void reportsAgainOnceAPulledImageGoesStaleAnew() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));
        tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE));   // operator pulled

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("a:1");
    }

    @Test
    void anUnknownVerdictNeitherReportsNorForgetsWhatWasKnown() {
        // The registry went unreachable for a sweep. That is not the operator pulling the image: when it comes
        // back still outdated, they must not be re-mailed about an image they were already told about.
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UNKNOWN))).isEmpty();
        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE))).isEmpty();
    }

    @Test
    void anImageThatIsUnknownFromTheStartIsNeverReported() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UNKNOWN))).isEmpty();
    }

    @Test
    void forgetsAnImageThatIsNoLongerRunningAnywhere() {
        // The container was removed; if that image ever comes back stale it is news again.
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.update(verdicts());

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("a:1");
    }

    @Test
    void reportsNewlyOutOfDateImagesInAStableAlphabeticalOrder() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();

        assertThat(tracker.update(verdicts(
            "zeta:1", UpdateAvailability.UPDATE_AVAILABLE,
            "alpha:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("alpha:1", "zeta:1");
    }
}
