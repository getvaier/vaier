package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackupFailureTrackerTest {

    @Test
    void alertsOnlyOnSuccessToFailureTransition() {
        BackupFailureTracker tracker = new BackupFailureTracker();

        // A first failing result is news worth paging (unlike a disk merely observed full at startup):
        // the per-job baseline is assumed-healthy, so the first failure crosses to failing.
        assertThat(tracker.update("colina-home", true))
            .isEqualTo(BackupFailureTracker.Transition.CROSSED_TO_FAILING);
    }

    @Test
    void firstSuccessIsBaselineNoTransition() {
        BackupFailureTracker tracker = new BackupFailureTracker();

        // A job that succeeds on its first observed result must not send a spurious all-clear.
        assertThat(tracker.update("colina-home", false))
            .isEqualTo(BackupFailureTracker.Transition.NONE);
    }

    @Test
    void steadyFailureIsNone() {
        BackupFailureTracker tracker = new BackupFailureTracker();
        tracker.update("colina-home", true); // first failure -> alert

        // Every subsequent nightly failure of the same job is steady state, not a new transition.
        assertThat(tracker.update("colina-home", true))
            .isEqualTo(BackupFailureTracker.Transition.NONE);
        assertThat(tracker.update("colina-home", true))
            .isEqualTo(BackupFailureTracker.Transition.NONE);
    }

    @Test
    void recoveryCrossesToHealthy() {
        BackupFailureTracker tracker = new BackupFailureTracker();
        tracker.update("colina-home", true); // failing

        assertThat(tracker.update("colina-home", false))
            .isEqualTo(BackupFailureTracker.Transition.CROSSED_TO_HEALTHY);
    }

    @Test
    void tracksEachJobIndependently() {
        BackupFailureTracker tracker = new BackupFailureTracker();

        // colina crosses to failing; roon must be unaffected (its own baseline is still healthy).
        assertThat(tracker.update("colina-home", true))
            .isEqualTo(BackupFailureTracker.Transition.CROSSED_TO_FAILING);
        assertThat(tracker.update("roon-media", false))
            .isEqualTo(BackupFailureTracker.Transition.NONE);
    }
}
