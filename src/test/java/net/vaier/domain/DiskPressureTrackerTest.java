package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiskPressureTrackerTest {

    @Test
    void firstObservation_isBaseline_noTransition() {
        DiskPressureTracker tracker = new DiskPressureTracker();

        assertThat(tracker.update(true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void crossingIntoPressure_reportsCrossedAbove() {
        DiskPressureTracker tracker = new DiskPressureTracker();
        tracker.update(false); // baseline: below threshold

        assertThat(tracker.update(true)).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
    }

    @Test
    void droppingBackBelow_reportsCrossedBelow() {
        DiskPressureTracker tracker = new DiskPressureTracker();
        tracker.update(true); // baseline: above threshold

        assertThat(tracker.update(false)).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
    }

    @Test
    void stayingAbove_reportsNone() {
        DiskPressureTracker tracker = new DiskPressureTracker();
        tracker.update(false);
        tracker.update(true);

        assertThat(tracker.update(true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void stayingBelow_reportsNone() {
        DiskPressureTracker tracker = new DiskPressureTracker();
        tracker.update(false);

        assertThat(tracker.update(false)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }
}
