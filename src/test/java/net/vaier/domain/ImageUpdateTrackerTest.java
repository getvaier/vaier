package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateTrackerTest {

    // --- #57 slice 3: what an operator-driven check may do to the alert state -------------------------
    //
    // The forced check is a partial-purpose observation: the operator is confirming their own pull, not
    // standing in for the mailer. So it may CLEAR an image's alert state and may never CONSUME one. That
    // asymmetry is the whole rule, and both halves of it are a real bug if dropped — see the two tests below.

    @Test
    void anImageFoundUpToDateByAForcedCheck_isAlertableAgainIfItGoesStaleLater() {
        // The silencing bug. Without this, a manual check that confirms a pull leaves the tracker still
        // believing the image is out of date — so when it genuinely goes stale again, the edge never fires
        // and the operator is never told. A button that quietly disables a future alarm is worse than no
        // button: they would trust a signal that had been switched off by their own diligence.
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        assertThat(tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("vaultwarden/server:latest");        // reported once

        tracker.clearUpToDate(Map.of("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("it went stale again — that is news again")
            .containsExactly("vaultwarden/server:latest");
    }

    @Test
    void aForcedCheckFindingAnImageStale_doesNotConsumeTheAlertTheMailerOwes() {
        // The swallowing bug, and the reason this is clearUpToDate rather than update(). If a forced check
        // recorded a NEWLY stale image as "seen", the daily sweep would then find previous=true and stay
        // silent — so clicking the button would have cost the operator the very email the feature exists to
        // send. A check may only ever clear good news; bad news stays the mailer's to break.
        ImageUpdateTracker tracker = new ImageUpdateTracker();

        tracker.clearUpToDate(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the mail the forced check must not have eaten")
            .containsExactly("vaultwarden/server:latest");
    }

    @Test
    void aForcedCheckDoesNotReMailAnImageAlreadyReported() {
        // The duplicate-mail rule. Still stale, already told them: clearing touches nothing, and the next
        // daily sweep still finds previous=true and stays quiet.
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.clearUpToDate(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .isEmpty();
    }

    @Test
    void anUnknownVerdictFromAForcedCheckClearsNothing() {
        // Same reasoning as update()'s: a rate-limited registry is not evidence the operator pulled. Reading
        // it as such would re-arm the alert and re-mail them about an image they were already told about.
        ImageUpdateTracker tracker = new ImageUpdateTracker();
        tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.clearUpToDate(Map.of("vaultwarden/server:latest", UpdateAvailability.UNKNOWN));

        assertThat(tracker.update(Map.of("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("nothing was learned, so nothing was cleared")
            .isEmpty();
    }

    @Test
    void clearingIsSafeOnAnImageTheTrackerHasNeverSeen() {
        ImageUpdateTracker tracker = new ImageUpdateTracker();

        tracker.clearUpToDate(Map.of("redis:7.2", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(Map.of("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly("redis:7.2");
    }

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
