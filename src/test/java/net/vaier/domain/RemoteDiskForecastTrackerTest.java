package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskForecastTrackerTest {

    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    private static final int LEVEL = 85;

    private static Instant hours(double h) {
        return T0.plusSeconds((long) (h * 3600));
    }

    /**
     * Feed a steady 1%/h climb below the level threshold. The runway sits at exactly the 24h horizon on
     * the third sample (76% → 24h, not yet under the horizon) and drops just under it on the fourth (77%
     * → 23h), so the returned observation is the one that crosses into the early-warning condition.
     */
    private RemoteDiskForecastTracker.Observation feedClimbToShortRunway(RemoteDiskForecastTracker tracker,
                                                                         String machine) {
        tracker.observe(machine, hours(0), 74, LEVEL);
        tracker.observe(machine, hours(1), 75, LEVEL);
        tracker.observe(machine, hours(2), 76, LEVEL); // runway exactly 24h → not yet warning
        return tracker.observe(machine, hours(3), 77, LEVEL); // 77%, 1%/h → 23h runway → crosses
    }

    @Test
    void firstObservation_isBaseline_noTransition() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();

        RemoteDiskForecastTracker.Observation obs = tracker.observe("nas", hours(0), 80, LEVEL);

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.NONE);
    }

    @Test
    void crossingIntoShortRunway_reportsCrossedAbove_withEarlyWarning() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();

        RemoteDiskForecastTracker.Observation obs = feedClimbToShortRunway(tracker, "nas");

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.CROSSED_ABOVE);
        assertThat(obs.earlyWarning()).isPresent();
        assertThat(obs.earlyWarning().get().runway()).isLessThan(Duration.ofHours(24));
        assertThat(obs.cleared()).isEmpty();
    }

    @Test
    void stayingWithinHorizon_doesNotReAlert() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();
        feedClimbToShortRunway(tracker, "nas"); // CROSSED_ABOVE

        // Another climbing sample, still below level and still short runway → no new crossing.
        RemoteDiskForecastTracker.Observation obs = tracker.observe("nas", hours(4), 81, LEVEL);

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.NONE);
        assertThat(obs.earlyWarning()).isEmpty();
        assertThat(obs.cleared()).isEmpty();
    }

    @Test
    void handoff_climbingPastLevelThreshold_suppressesForecastClear() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();
        feedClimbToShortRunway(tracker, "nas"); // warned at 77%

        // Next sample pushes above the level threshold → the disk-pressure alert owns it now, so the
        // forecast clear must be suppressed (no contradictory double-page at the same poll).
        RemoteDiskForecastTracker.Observation obs = tracker.observe("nas", hours(4), 86, LEVEL);

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
        assertThat(obs.cleared()).isEmpty();
    }

    @Test
    void genuineRecovery_drainingBelowThreshold_emitsClearedWithCurrentPercent() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();
        feedClimbToShortRunway(tracker, "nas"); // warned at 77%

        // Someone freed space: a sharp drop drives the recent slope non-positive (no runway), while the
        // disk stays below the level threshold → this is a genuine recovery, so an all-clear is emitted.
        RemoteDiskForecastTracker.Observation obs = tracker.observe("nas", hours(4), 50, LEVEL);

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
        assertThat(obs.earlyWarning()).isEmpty();
        assertThat(obs.cleared()).isPresent();
        assertThat(obs.cleared().get().machineName()).isEqualTo("nas");
        assertThat(obs.cleared().get().currentPercent()).isEqualTo(50);
    }

    @Test
    void genuineRecovery_fillSlowedBelowThreshold_emitsCleared() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();
        feedClimbToShortRunway(tracker, "nas"); // warned at 77%

        // Fill slows to a plateau: runway rises back above the 24h horizon while staying below the level
        // threshold → genuine recovery, all-clear emitted with the current percent.
        RemoteDiskForecastTracker.Observation obs = tracker.observe("nas", hours(4), 77, LEVEL);

        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.CROSSED_BELOW);
        assertThat(obs.cleared()).isPresent();
        assertThat(obs.cleared().get().currentPercent()).isEqualTo(77);
    }

    @Test
    void tracksEachMachineIndependently() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker();

        feedClimbToShortRunway(tracker, "nas"); // nas warns

        // nuc has only a baseline observation; it must not inherit nas's warning state.
        RemoteDiskForecastTracker.Observation obs = tracker.observe("nuc", hours(3), 80, LEVEL);
        assertThat(obs.transition()).isEqualTo(DiskPressureTracker.Transition.NONE);
    }
}
