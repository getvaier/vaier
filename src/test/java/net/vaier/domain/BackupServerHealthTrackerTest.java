package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServerHealthTrackerTest {

    @Test
    void singleFailureDoesNotPage() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();

        // A single failed probe is a blip; it must never cross to DOWN (no page).
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
    }

    @Test
    void twoConsecutiveFailuresCrossToDown() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();

        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        // The second consecutive failed probe confirms the server is really down.
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.CROSSED_TO_DOWN);
    }

    @Test
    void thirdFailureDoesNotRePage() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();
        tracker.update("nas-borg", false);
        tracker.update("nas-borg", false); // crosses to down

        // Steady-down state must not re-alert on every sweep.
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
    }

    @Test
    void successAfterDownCrossesToHealthy() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();
        tracker.update("nas-borg", false);
        tracker.update("nas-borg", false); // down

        // A single successful probe immediately restores healthy and sends the all-clear.
        assertThat(tracker.update("nas-borg", true))
            .isEqualTo(BackupServerHealthTracker.Transition.CROSSED_TO_HEALTHY);
    }

    @Test
    void successWhileHealthyIsNone() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();

        // A fresh tracker is seeded assumed-healthy, so a healthy probe is not a transition.
        assertThat(tracker.update("nas-borg", true))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", true))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
    }

    @Test
    void oneFailureThenSuccessResetsTheStrikeCount() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();

        // One failure (strike 1), then a success clears it; the next failure is only strike 1 again,
        // so it must not immediately cross to down.
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", true))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.CROSSED_TO_DOWN);
    }

    @Test
    void tracksEachServerIndependently() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();

        tracker.update("nas-borg", false);
        // A second server's failures must not be affected by the first server's strike count.
        assertThat(tracker.update("pi-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.CROSSED_TO_DOWN);
        assertThat(tracker.update("pi-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.CROSSED_TO_DOWN);
    }

    @Test
    void forgettingAServerResetsItsState() {
        BackupServerHealthTracker tracker = new BackupServerHealthTracker();
        tracker.update("nas-borg", false); // strike 1

        tracker.forget("nas-borg");

        // After forgetting, the server is seeded healthy again: a single failure is strike 1, no page.
        assertThat(tracker.update("nas-borg", false))
            .isEqualTo(BackupServerHealthTracker.Transition.NONE);
    }
}
