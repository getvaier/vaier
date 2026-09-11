package net.vaier.domain;

import net.vaier.adapter.driven.InMemoryMissingDefaultRouteAdapter;
import net.vaier.domain.port.ForPersistingMissingDefaultRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.vaier.domain.MissingDefaultRouteTracker.Outcome.ALERT;
import static net.vaier.domain.MissingDefaultRouteTracker.Outcome.QUIET;
import static net.vaier.domain.MissingDefaultRouteTracker.Outcome.RECOVERED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decides, per machine, whether admins should hear that it has lost its way out (#357). The shape is the
 * two-state cousin of {@link RemoteDiskPressureTracker}: there are no bands to escalate through here, only
 * "has a route" and "has none", so the whole rule is the first observation, the transition, and the
 * recovery — with {@link DefaultRouteStanding#UNKNOWN} never touching the latch.
 */
class MissingDefaultRouteTrackerTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final MachineId NUC = TestMachineIds.of("nuc");

    private ForPersistingMissingDefaultRoutes alerted;
    private MissingDefaultRouteTracker tracker;

    @BeforeEach
    void setUp() {
        alerted = new InMemoryMissingDefaultRouteAdapter();
        tracker = new MissingDefaultRouteTracker(alerted);
    }

    @Test
    void theVeryFirstObservationOfAMachineWithNoRouteSpeaks() {
        // The lesson the disk work paid for: a first observation treated as a silent baseline means a fault
        // that was already there when Vaier started is never reported at all. With the latch on disk, "first
        // ever" genuinely means first ever, so the first ABSENT must speak.
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(ALERT);
    }

    @Test
    void aRouteThatGoesMissingSpeaksOnce_andThenStaysQuiet() {
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.PRESENT)).isEqualTo(QUIET);
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(ALERT);
        // Five minutes later, and every five minutes after that. Nothing new has happened, so nothing is
        // said — this project does not page on the passage of time.
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(QUIET);
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(QUIET);
    }

    @Test
    void theRouteComingBackIsWorthSayingOnce() {
        tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT);

        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.PRESENT)).isEqualTo(RECOVERED);
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.PRESENT)).isEqualTo(QUIET);
    }

    @Test
    void aRecoveredMachineThatLosesItsRouteAgainIsAlertedAgain() {
        tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT);
        tracker.observe(APALVEIEN, DefaultRouteStanding.PRESENT);

        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(ALERT);
    }

    @Test
    void aHealthyMachineNobodyEverAlertedAboutIsSilent() {
        assertThat(tracker.observe(NUC, DefaultRouteStanding.PRESENT)).isEqualTo(QUIET);
        assertThat(alerted.wasAlerted(NUC)).isFalse();
    }

    @Test
    void unknownNeverMovesTheLatchInEitherDirection() {
        // Unknown is not "no", and it is not "yes" either. A machine Vaier could not read must neither raise
        // an alert nor cancel one that is standing — a sweep that fails must not quietly declare a recovery.
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.UNKNOWN)).isEqualTo(QUIET);
        assertThat(alerted.wasAlerted(APALVEIEN)).isFalse();

        tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT);
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.UNKNOWN)).isEqualTo(QUIET);
        assertThat(alerted.wasAlerted(APALVEIEN)).isTrue();
        // …and when the machine can be read again, it is still the same standing alert, not a new one.
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(QUIET);
    }

    @Test
    void oneMachinesFaultIsNeverAnothersSilence() {
        assertThat(tracker.observe(APALVEIEN, DefaultRouteStanding.ABSENT)).isEqualTo(ALERT);
        assertThat(tracker.observe(NUC, DefaultRouteStanding.ABSENT)).isEqualTo(ALERT);
    }
}
