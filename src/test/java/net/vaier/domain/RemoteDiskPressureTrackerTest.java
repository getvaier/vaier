package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskPressureTrackerTest {

    @Test
    void firstObservationPerMachine_isBaseline_noTransition() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();

        assertThat(tracker.update("nas", true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void crossingIntoPressure_reportsCrossedAbove() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", false);

        assertThat(tracker.update("nas", true)).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
    }

    @Test
    void droppingBackBelow_reportsCrossedBelow() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", true);

        assertThat(tracker.update("nas", false)).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
    }

    @Test
    void stayingAbove_reportsNone() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", false);
        tracker.update("nas", true);

        assertThat(tracker.update("nas", true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void tracksEachMachineIndependently() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", false);
        tracker.update("nuc", false);

        // nas crosses above; nuc must be unaffected (still baseline-below → NONE on its next below)
        assertThat(tracker.update("nas", true)).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
        assertThat(tracker.update("nuc", false)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }
}
