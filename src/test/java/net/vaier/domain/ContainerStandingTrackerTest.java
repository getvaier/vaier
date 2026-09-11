package net.vaier.domain;

import net.vaier.adapter.driven.InMemoryContainerStandingAdapter;
import net.vaier.domain.ContainerStandingTracker.Outcome;
import net.vaier.domain.ContainerStandingTracker.Verdict;
import net.vaier.domain.port.ForPersistingContainerStandings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decides, per container, whether admins should hear that something that was running is not running any
 * more (#356). The honest trigger is never "a container is stopped" — plenty are stopped on purpose,
 * forever — but "this one was up when Vaier last looked and is down now".
 */
class ContainerStandingTrackerTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final MachineId NUC = TestMachineIds.of("nuc");
    private static final Instant NOON = Instant.parse("2026-09-11T12:00:00Z");

    private ForPersistingContainerStandings standings;
    private ContainerStandingTracker tracker;

    @BeforeEach
    void setUp() {
        standings = new InMemoryContainerStandingAdapter();
        tracker = new ContainerStandingTracker(standings);
    }

    private static DockerService container(String name, String state) {
        return new DockerService("id-" + name, name, "ghcr.io/" + name + ":latest", "v",
            List.of(), List.of(), state);
    }

    private List<Verdict> scrape(MachineId machineId, Instant at, DockerService... containers) {
        return tracker.observe(
            ContainerObservation.of(machineId.value(), "OK", List.of(containers)).orElseThrow(), at);
    }

    private List<Verdict> scrape(Instant at, DockerService... containers) {
        return scrape(APALVEIEN, at, containers);
    }

    private static Instant minutesAfterNoon(int minutes) {
        return NOON.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void aContainerSeenRunningIsRememberedAndSaysNothing() {
        assertThat(scrape(NOON, container("webtrees", "running"))).isEmpty();

        assertThat(standings.standingsFor(APALVEIEN))
            .singleElement()
            .satisfies(standing -> {
                assertThat(standing.containerName()).isEqualTo("webtrees");
                assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING);
                assertThat(standing.lastSeenRunning()).isEqualTo(NOON);
            });
    }

    @Test
    void oneMissedScrapeIsNeverNews() {
        // A Vaier-driven update recreates a container, and a restart is a moment of being down. One
        // sighting of a stopped container is never worth an email.
        scrape(NOON, container("webtrees", "running"));

        assertThat(scrape(minutesAfterNoon(1), container("webtrees", "exited"))).isEmpty();
    }

    @Test
    void twoConsecutiveMissesAreTheAlert_andTheStandingIsGone() {
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1), container("webtrees", "exited"));

        List<Verdict> verdicts = scrape(minutesAfterNoon(2), container("webtrees", "exited"));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.outcome()).isEqualTo(Outcome.ALERT);
            assertThat(verdict.standing().containerName()).isEqualTo("webtrees");
            assertThat(verdict.standing().standing()).isEqualTo(ContainerStanding.GONE);
            // What the mail and the card both say: when Vaier last saw it up, and when it stopped being.
            assertThat(verdict.standing().lastSeenRunning()).isEqualTo(NOON);
            assertThat(verdict.standing().notRunningSince()).isEqualTo(minutesAfterNoon(1));
        });
    }

    @Test
    void aContainerThatIsToldAboutIsNotToldAboutAgain() {
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1), container("webtrees", "exited"));
        scrape(minutesAfterNoon(2), container("webtrees", "exited"));

        // Every 30 seconds, for as long as it stays down. This project does not page on the passage of time.
        assertThat(scrape(minutesAfterNoon(3), container("webtrees", "exited"))).isEmpty();
        assertThat(scrape(minutesAfterNoon(4), container("webtrees", "exited"))).isEmpty();
        // …and it keeps its standing, so the machine's card goes on saying so.
        assertThat(standings.standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing -> assertThat(standing.standing()).isEqualTo(ContainerStanding.GONE));
    }

    @Test
    void aContainerComingBackIsTheAllClear_once() {
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1), container("webtrees", "exited"));
        scrape(minutesAfterNoon(2), container("webtrees", "exited"));

        assertThat(scrape(minutesAfterNoon(3), container("webtrees", "running")))
            .singleElement()
            .satisfies(verdict -> assertThat(verdict.outcome()).isEqualTo(Outcome.RECOVERED));
        assertThat(scrape(minutesAfterNoon(4), container("webtrees", "running"))).isEmpty();
    }

    @Test
    void aContainerNeverSeenRunningIsNeverNews() {
        // The stopped-on-purpose case: a one-shot init container, a stack the operator retired. Vaier has
        // never seen it up, so there is no "was up, now isn't" to report — and nothing to remember either.
        scrape(NOON, container("dex-init", "exited"));
        scrape(minutesAfterNoon(1), container("dex-init", "exited"));

        assertThat(scrape(minutesAfterNoon(2), container("dex-init", "exited"))).isEmpty();
        assertThat(standings.standingsFor(APALVEIEN)).isEmpty();
    }

    @Test
    void aMachineThatWasNotScrapedMovesNothing() {
        scrape(NOON, container("webtrees", "running"));

        ContainerObservation unanswered =
            ContainerObservation.of(APALVEIEN.value(), "UNREACHABLE", List.of()).orElseThrow();
        assertThat(tracker.observe(unanswered, minutesAfterNoon(1))).isEmpty();
        assertThat(tracker.observe(unanswered, minutesAfterNoon(2))).isEmpty();

        // No answer is not evidence that a container is down — a machine asleep must never mail about
        // every container on it.
        assertThat(standings.standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing -> {
                assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING);
                assertThat(standing.lastSeenRunning()).isEqualTo(NOON);
            });
    }

    @Test
    void aContainerThatRunsAgainBeforeTheSecondMissResetsTheCount() {
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1), container("webtrees", "exited"));
        scrape(minutesAfterNoon(2), container("webtrees", "running"));

        assertThat(scrape(minutesAfterNoon(3), container("webtrees", "exited"))).isEmpty();
        assertThat(standings.standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing -> assertThat(standing.lastSeenRunning()).isEqualTo(minutesAfterNoon(2)));
    }

    @Test
    void aRemovedContainerCostsExactlyOneMail_andThenIsForgotten() {
        // Deliberately removed rather than merely stopped: it is gone from the scrape entirely. Worth
        // saying once, and then it must not leave a card standing on the machine forever.
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1));

        assertThat(scrape(minutesAfterNoon(2)))
            .singleElement()
            .satisfies(verdict -> assertThat(verdict.outcome()).isEqualTo(Outcome.ALERT));
        assertThat(standings.standingsFor(APALVEIEN)).isEmpty();
        assertThat(scrape(minutesAfterNoon(3))).isEmpty();
    }

    @Test
    void aGoneContainerThatIsLaterRemovedStopsBeingRemembered() {
        scrape(NOON, container("webtrees", "running"));
        scrape(minutesAfterNoon(1), container("webtrees", "exited"));
        scrape(minutesAfterNoon(2), container("webtrees", "exited"));

        assertThat(scrape(minutesAfterNoon(3))).isEmpty();
        assertThat(standings.standingsFor(APALVEIEN)).isEmpty();
    }

    @Test
    void oneMachinesContainersAreNeverAnothers() {
        // Two machines in this fleet really can run a container of the same name.
        scrape(APALVEIEN, NOON, container("webtrees", "running"));
        scrape(NUC, NOON, container("webtrees", "running"));
        scrape(NUC, minutesAfterNoon(1), container("webtrees", "exited"));

        assertThat(scrape(NUC, minutesAfterNoon(2), container("webtrees", "exited")))
            .singleElement()
            .satisfies(verdict -> assertThat(verdict.standing().machineId()).isEqualTo(NUC));
        assertThat(standings.standingsFor(APALVEIEN)).singleElement()
            .satisfies(standing -> assertThat(standing.standing()).isEqualTo(ContainerStanding.RUNNING));
    }

    @Test
    void theAlertCarriesTheMachinesBootInstantWhenTheSweepHasLearnedOne() {
        // The context that makes the whole thing legible: the machine rebooted, and a container with
        // `restart: no` did not come back with it.
        scrape(NOON, container("webtrees", "running"));
        standings.recordBoot(APALVEIEN, minutesAfterNoon(1));
        scrape(minutesAfterNoon(2), container("webtrees", "exited"));

        assertThat(scrape(minutesAfterNoon(3), container("webtrees", "exited")))
            .singleElement()
            .satisfies(verdict ->
                assertThat(verdict.standing().machineBootedAt()).isEqualTo(minutesAfterNoon(1)));
    }

    @Test
    void nothingToSayIsNothingToPublish() {
        assertThat(Verdict.worthPublishing(List.of())).isFalse();
        assertThat(Verdict.worthPublishing(scrape(NOON, container("webtrees", "running")))).isFalse();
    }
}
