package net.vaier.domain;

import net.vaier.domain.port.ForPersistingContainerStandings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides, per container, whether admins should hear that something which was running is in trouble now
 * (#356, widened in #317). It owns the whole rule — what counts as evidence, how many misses are news, what a
 * recovery is, and when to stop remembering a container at all — and reaches its own memory through
 * {@link ForPersistingContainerStandings}; the scheduler hands the port in and then only decides whom to
 * tell.
 *
 * <p>The cousin of {@link MissingDefaultRouteTracker}, one level finer: that one judges a machine, this one
 * judges every container on it. The rules that matter:
 *
 * <ul>
 *   <li><b>A machine that did not answer moves nothing.</b> No answer is not evidence that a container is
 *       down, and a machine asleep must never mail about every container on it.</li>
 *   <li><b>A container never seen running is never news.</b> Plenty are stopped on purpose, forever —
 *       one-shot init containers, a retired stack. Vaier remembers only what it has watched run, so it has
 *       nothing to say about the rest.</li>
 *   <li><b>Two consecutive misses, not one.</b> One is a Vaier-driven update recreating the container, or
 *       a restart in progress. Neither is worth an email, and an alert that fires on them is an alert
 *       people learn to ignore.</li>
 *   <li><b>Exit code is not the filter.</b> The container that prompted this exited 255 with
 *       {@code OOMKilled: false} after a clean shutdown — a rule keyed on "non-zero exit" would have called
 *       that a crash and been wrong. "Was up, now isn't" gets it right.</li>
 * </ul>
 */
public class ContainerStandingTracker {

    /**
     * How many consecutive answered scrapes must find a container not running before it is news. Two, at
     * 30 seconds apart, so a restart has a minute to finish before anybody is told about it.
     */
    private static final int MISSES_BEFORE_ALERT = 2;

    /** What Vaier should do about one container after this scrape. */
    public enum Outcome {
        /** It was running and has stopped, and admins have not been told — send the alert. */
        ALERT,
        /** It is running again after admins were told it was gone — send the all-clear. */
        RECOVERED
    }

    /**
     * One thing worth saying, and the standing that says it. Only transitions reach a verdict at all —
     * a container going on being fine, or going on being gone, produces none.
     */
    public record Verdict(Outcome outcome, MachineContainerStanding standing) {

        /**
         * Whether a scrape's verdicts are worth waking an open Explorer for. Nothing moved means nothing to
         * repaint — the same rule the update sweep follows, and the reason a healthy fleet costs the browser
         * no traffic at all.
         */
        public static boolean worthPublishing(List<Verdict> verdicts) {
            return verdicts != null && !verdicts.isEmpty();
        }
    }

    private final ForPersistingContainerStandings standings;

    public ContainerStandingTracker(ForPersistingContainerStandings standings) {
        this.standings = standings;
    }

    /**
     * Record what one scrape of one machine found, and decide what admins should hear about each of its
     * containers.
     *
     * @param observation what the scrape saw, and whether it got an answer at all
     * @param at          when it saw it
     */
    public synchronized List<Verdict> observe(ContainerObservation observation, Instant at) {
        if (!observation.answered()) {
            return List.of();
        }
        MachineId machineId = observation.machineId();
        Instant bootedAt = standings.bootOf(machineId).orElse(null);
        Map<String, MachineContainerStanding> remembered = remembered(machineId);

        List<Verdict> verdicts = new ArrayList<>();
        List<MachineContainerStanding> next = new ArrayList<>();
        for (DockerService container : observation.containers()) {
            MachineContainerStanding prior = remembered.remove(container.containerName());
            judgeSeen(machineId, container, prior, at, bootedAt, next, verdicts);
        }
        // Whatever is left was not in the scrape at all — removed rather than merely stopped.
        remembered.values().forEach(absent -> judgeAbsent(absent, at, bootedAt, next, verdicts));

        standings.record(machineId, next);
        return List.copyOf(verdicts);
    }

    /** A container the scrape actually saw — running well, running badly, restarting, or stopped. */
    private void judgeSeen(MachineId machineId, DockerService container, MachineContainerStanding prior,
                           Instant at, Instant bootedAt, List<MachineContainerStanding> next,
                           List<Verdict> verdicts) {
        Optional<ContainerStanding> reading = ContainerStanding.read(container);
        if (reading.isEmpty()) {
            // The health check has not concluded. Vaier has taken no verdict, so nothing moves — the
            // standing is held exactly where it was rather than counted either way.
            if (prior != null) {
                next.add(prior.withMachineBootedAt(bootedAt));
            }
            return;
        }
        ContainerStanding read = reading.get();
        if (read == ContainerStanding.RUNNING) {
            MachineContainerStanding running =
                MachineContainerStanding.seenRunning(machineId, container.containerName(), at, bootedAt);
            next.add(running);
            if (prior != null && prior.isTrouble()) {
                verdicts.add(new Verdict(Outcome.RECOVERED, running));
            }
            return;
        }
        if (prior == null) {
            // Never seen running and well. Nothing to compare it against, so nothing to say — ever.
            return;
        }
        if (prior.standing() == read) {
            // The same trouble, already said. It keeps its standing so the machine's card goes on saying
            // so, and the inbox stays quiet.
            next.add(prior.withMachineBootedAt(bootedAt));
            return;
        }
        MachineContainerStanding missed = prior.troubled(read, at, bootedAt);
        if (missed.misses() < MISSES_BEFORE_ALERT) {
            next.add(missed);
            return;
        }
        // Said twice running: the standing moves, so the card and the badge are honest about what is
        // happening now. Whether it is also worth an email is the separate, stricter question.
        boolean escalation = missed.isEscalation();
        MachineContainerStanding announced = missed.announce();
        next.add(announced);
        if (escalation) {
            verdicts.add(new Verdict(Outcome.ALERT, announced));
        }
    }

    /**
     * A container Vaier remembers that this scrape did not see at all — removed, not merely stopped.
     *
     * <p>It earns the same one alert as a stopped one, and is then <b>forgotten</b>: a container that no
     * longer exists must not leave a card standing on the machine forever, so a deliberate removal costs
     * exactly one email and nothing after it.
     */
    private void judgeAbsent(MachineContainerStanding absent, Instant at, Instant bootedAt,
                             List<MachineContainerStanding> next, List<Verdict> verdicts) {
        if (absent.isGone()) {
            return;
        }
        MachineContainerStanding missed = absent.troubled(ContainerStanding.GONE, at, bootedAt);
        if (missed.misses() < MISSES_BEFORE_ALERT) {
            next.add(missed);
            return;
        }
        boolean escalation = missed.isEscalation();
        MachineContainerStanding gone = missed.announce();
        if (escalation) {
            verdicts.add(new Verdict(Outcome.ALERT, gone));
        }
    }

    private Map<String, MachineContainerStanding> remembered(MachineId machineId) {
        Map<String, MachineContainerStanding> byName = new LinkedHashMap<>();
        standings.standingsFor(machineId)
            .forEach(standing -> byName.put(standing.containerName(), standing));
        return byName;
    }
}
