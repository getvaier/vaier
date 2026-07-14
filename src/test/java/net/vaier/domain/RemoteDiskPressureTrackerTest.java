package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskPressureTrackerTest {

    @Test
    void firstObservationPerMachine_isBaseline_noTransition() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();

        assertThat(tracker.update("nas", "/", true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void crossingIntoPressure_reportsCrossedAbove() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", false);

        assertThat(tracker.update("nas", "/", true)).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
    }

    @Test
    void droppingBackBelow_reportsCrossedBelow() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", true);

        assertThat(tracker.update("nas", "/", false)).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
    }

    @Test
    void stayingAbove_reportsNone() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", false);
        tracker.update("nas", "/", true);

        assertThat(tracker.update("nas", "/", true)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void tracksEachMachineIndependently() {
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", false);
        tracker.update("nuc", "/", false);

        // nas crosses above; nuc must be unaffected (still baseline-below → NONE on its next below)
        assertThat(tracker.update("nas", "/", true)).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
        assertThat(tracker.update("nuc", "/", false)).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void tracksEachFilesystemOfOneMachineIndependently() {
        // #325. This is why the key had to grow a mount point. On the NAS, / sits permanently above the
        // threshold (the 2.3 GB DSM system partition, 88% by design), so a machine-keyed tracker is already
        // "in pressure" — and /volume1 crossing into pressure would be swallowed as "no change". The disk
        // that actually matters would never be heard. That is exactly the silence this issue is about.
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", true);            // the system partition: permanently above
        tracker.update("nas", "/volume1", false);    // the volume: fine, for now

        assertThat(tracker.update("nas", "/volume1", true))
            .isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
        assertThat(tracker.update("nas", "/", true))
            .isEqualTo(DiskPressureTracker.Transition.NONE);   // still above, still quiet
    }

    @Test
    void oneMountPointOnTwoMachines_isTwoFilesystems() {
        // "/" is 88% by design on the NAS and an emergency on Apalveien 5. Keyed on the mount alone, one
        // would speak for the other.
        RemoteDiskPressureTracker tracker = new RemoteDiskPressureTracker();
        tracker.update("nas", "/", false);
        tracker.update("apalveien", "/", false);

        assertThat(tracker.update("nas", "/", true))
            .isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
        assertThat(tracker.update("apalveien", "/", false))
            .isEqualTo(DiskPressureTracker.Transition.NONE);
    }
}
