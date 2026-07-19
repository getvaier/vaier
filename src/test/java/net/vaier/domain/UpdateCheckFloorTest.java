package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The floor under the operator's own update check (#57 slice 3).
 *
 * <p>The daily sweep is protected from the anonymous rate limit by being daily. A button is not protected by
 * anything, and it deliberately bypasses the digest cache — so it is the one path in Vaier that can turn an
 * impatient human into ~100 manifest requests. Docker Hub answers that with a 429, every image degrades to
 * unknown, and the fleet goes blind exactly when the operator is trying to see it. Hence a floor.
 */
class UpdateCheckFloorTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-17T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    @Test
    void theFirstCheckIsAlwaysAdmitted() {
        MutableClock clock = new MutableClock();

        UpdateCheckFloor.Admission admission = new UpdateCheckFloor(clock).admit();

        assertThat(admission.admitted()).isTrue();
        assertThat(admission.lastCheckedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aSecondCheckMomentsLaterIsRefused() {
        // The click that follows a click. Nothing upstream can have moved in two seconds, and the registries
        // must not be asked to confirm it.
        UpdateCheckFloor floor = new UpdateCheckFloor(new MutableClock());
        floor.admit();

        assertThat(floor.admit().admitted()).isFalse();
    }

    @Test
    void aCheckIsAdmittedAgainOnceTheFloorHasPassed() {
        MutableClock clock = new MutableClock();
        UpdateCheckFloor floor = new UpdateCheckFloor(clock);
        floor.admit();

        clock.advance(Duration.ofSeconds(61));

        assertThat(floor.admit().admitted()).isTrue();
    }

    @Test
    void theFloorIsMeasuredFromTheLastAdmittedCheck_notTheLastAttempt() {
        // Otherwise an operator clicking every 30s would push the floor ahead of itself forever and never be
        // allowed to check at all — a rate limiter that punishes impatience with a permanent refusal.
        MutableClock clock = new MutableClock();
        UpdateCheckFloor floor = new UpdateCheckFloor(clock);
        floor.admit();                          // admitted at T+0

        clock.advance(Duration.ofSeconds(40));
        assertThat(floor.admit().admitted()).as("still inside the floor").isFalse();

        clock.advance(Duration.ofSeconds(25));  // T+65: 65s since the admitted check, 25s since the refusal
        assertThat(floor.admit().admitted()).as("the refusal must not have restarted the floor").isTrue();
    }

    @Test
    void aRefusedCheckReportsWhenVaierLastReallyLooked() {
        // The whole point of refusing honestly rather than pretending: the operator is told when the answer
        // they are looking at was actually obtained, and can decide whether that is good enough.
        MutableClock clock = new MutableClock();
        UpdateCheckFloor floor = new UpdateCheckFloor(clock);
        Instant admittedAt = floor.admit().lastCheckedAt();

        clock.advance(Duration.ofSeconds(10));

        assertThat(floor.admit().lastCheckedAt())
            .as("the refusal reports the real check, not the moment of refusal")
            .isEqualTo(admittedAt);
    }

    @Test
    void anAdmittedCheckBecomesTheNewLastCheckedAt() {
        MutableClock clock = new MutableClock();
        UpdateCheckFloor floor = new UpdateCheckFloor(clock);
        floor.admit();

        clock.advance(Duration.ofSeconds(90));

        assertThat(floor.admit().lastCheckedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aBurstOfClicksIsOneQuestionToTheRegistries() {
        // The rate-limit floor stated as the thing it actually protects: ten impatient clicks must not become
        // ten fleet-wide forced sweeps, because ~100 manifest requests per six hours is the whole budget.
        MutableClock clock = new MutableClock();
        UpdateCheckFloor floor = new UpdateCheckFloor(clock);

        long admitted = java.util.stream.IntStream.range(0, 10)
            .peek(i -> clock.advance(Duration.ofSeconds(2)))
            .filter(i -> floor.admit().admitted())
            .count();

        assertThat(admitted).isEqualTo(1);
    }
}
