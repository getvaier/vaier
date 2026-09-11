package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words Vaier says about a container that was running and is not any more (#356) — the same facts in
 * the email and on the machine's card, written once so the two can never drift apart.
 */
class MachineContainerStandingTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final Instant SEEN = Instant.parse("2026-09-11T09:15:00Z");
    private static final Instant STOPPED = Instant.parse("2026-09-11T09:47:00Z");
    /** Where the operator reads their mail — the server's own zone, handed in from its Clock. */
    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");

    private static MachineContainerStanding gone(Instant bootedAt) {
        return MachineContainerStanding.builder()
            .machineId(APALVEIEN)
            .containerName("webtrees")
            .standing(ContainerStanding.GONE)
            .lastSeenRunning(SEEN)
            .notRunningSince(STOPPED)
            .machineBootedAt(bootedAt)
            .misses(2)
            .build();
    }

    @Test
    void theSubjectNamesTheContainerAndTheMachine() {
        assertThat(gone(null).goneSubject("Apalveien 5"))
            .isEqualTo("[Vaier] webtrees is not running on Apalveien 5");
    }

    @Test
    void theBodySaysWhenItWasUp_whenItStopped_andThatVaierCannotStartIt() {
        String body = gone(null).goneBody("Apalveien 5", OSLO);

        assertThat(body)
            .contains("Apalveien 5")
            .contains("webtrees")
            .contains("cannot start it");
    }

    @Test
    void everyTimeIsWrittenInTheZoneTheOperatorReadsItIn() {
        // The mail is read by a person in Oslo, not by a log parser. UTC made them do the arithmetic on
        // every line — "07:21" for something that happened at 09:21 — so the server's own zone is what
        // the words carry, named in full so there is nothing to work out.
        MachineContainerStanding rebooted = gone(Instant.parse("2026-09-11T09:40:00Z"));

        assertThat(rebooted.goneBody("Apalveien 5", OSLO))
            .contains("2026-09-11 11:15 (Europe/Oslo)")
            .contains("2026-09-11 11:47 (Europe/Oslo)")
            .contains("2026-09-11 11:40 (Europe/Oslo)")
            .doesNotContain("UTC");
        assertThat(rebooted.evidence(OSLO)).contains("(Europe/Oslo)");
    }

    @Test
    void aZoneOfItsOwnIsAllThatChanges_neverTheInstantItself() {
        // The stored instants stay UTC; only the words move. Handed UTC, it says UTC o'clock.
        assertThat(gone(null).goneBody("Apalveien 5", ZoneId.of("UTC")))
            .contains("2026-09-11 09:15 (UTC)");
    }

    @Test
    void aRebootAfterTheContainerWasLastSeenRunningIsWhatExplainsIt() {
        MachineContainerStanding rebooted = gone(Instant.parse("2026-09-11T09:40:00Z"));

        assertThat(rebooted.rebootExplanation(OSLO)).isPresent();
        assertThat(rebooted.goneBody("Apalveien 5", OSLO)).contains("rebooted at 2026-09-11 11:40");
        assertThat(rebooted.evidence(OSLO)).contains("rebooted at 2026-09-11 11:40");
    }

    @Test
    void aBootInstantTheSweepNeverLearnedIsSimplyNotMentioned() {
        // Unknown is not a story. A guess dressed as context is worse than no context at all.
        assertThat(gone(null).rebootExplanation(OSLO)).isEmpty();
        assertThat(gone(null).goneBody("Apalveien 5", OSLO)).doesNotContain("rebooted");
    }

    @Test
    void aMachineThatHasNotRebootedSinceExplainsNothing() {
        // It booted last week and the container stopped this morning: the reboot is not the reason.
        assertThat(gone(Instant.parse("2026-09-04T06:00:00Z")).rebootExplanation(OSLO)).isEmpty();
    }

    @Test
    void theAllClearSaysTheContainerIsRunningAgain_andNothingElse() {
        MachineContainerStanding back =
            MachineContainerStanding.seenRunning(APALVEIEN, "webtrees", STOPPED, null);

        assertThat(back.backSubject("Apalveien 5"))
            .isEqualTo("[Vaier] webtrees is running again on Apalveien 5");
        assertThat(back.backBody("Apalveien 5", OSLO)).contains("2026-09-11 11:47 (Europe/Oslo)")
            .doesNotContain("cannot start it");
    }

    @Test
    void aContainerJustSeenRunningStartsFromNothing() {
        MachineContainerStanding running =
            MachineContainerStanding.seenRunning(APALVEIEN, "webtrees", SEEN, null);

        assertThat(running.standing()).isEqualTo(ContainerStanding.RUNNING);
        assertThat(running.isGone()).isFalse();
        assertThat(running.misses()).isZero();
        assertThat(running.notRunningSince()).isNull();
    }

    @Test
    void theFirstMissIsWhatDatesIt_andLaterMissesLeaveThatAlone() {
        MachineContainerStanding running =
            MachineContainerStanding.seenRunning(APALVEIEN, "webtrees", SEEN, null);

        MachineContainerStanding twice = running.missed(STOPPED, null)
            .missed(STOPPED.plusSeconds(30), null);

        assertThat(twice.misses()).isEqualTo(2);
        assertThat(twice.notRunningSince()).isEqualTo(STOPPED);
    }
}
