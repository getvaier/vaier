package net.vaier.domain;

import java.util.Optional;

/**
 * Where one container on one machine stands, as far as Vaier has seen (#356, widened in #317). Vaier only
 * ever remembers a container it has actually watched run well — a container that has never been seen
 * running has no standing at all, and that absence is the whole reason this can be quiet about the many
 * containers that are stopped on purpose, forever.
 *
 * <p>{@link #RUNNING} — Vaier saw it running, and its health check (if it has one) passing, on its last
 * answered scrape of that machine.
 * {@link #UNHEALTHY} — it is running and its own health check has failed on two consecutive scrapes.
 * {@link #RESTARTING} — Docker is restarting it over and over: a restart loop.
 * {@link #GONE} — it was running, and two consecutive scrapes since have found it not running. Two,
 * because one is a Vaier-driven update or a restart in progress, and neither of those is news.
 *
 * <p><b>The order of these constants is the order of badness</b>, and {@link #isWorseThan} reads it.
 * That is what decides whether a container that has changed trouble is worth a second email: worse news
 * is news (an unhealthy container that finally exits), better news waits for the all-clear (a gone
 * container that comes back up but unwell).
 *
 * <p><b>#356.</b> A machine rebooted, the containers with a restart policy came back, one with
 * {@code restart: no} did not, and Vaier reported the machine green on every axis it watched — reachable,
 * disk fine, backups green — because every one of those things was true. The information was already on
 * the wire: the 30-second scrape had seen that container running and now saw it stopped.
 *
 * <p><b>#317</b> takes the rest of what that same reading already says. A container can be in trouble
 * long before it is gone — its health check failing, or Docker restarting it in a loop — and the restart
 * loop is the case that may never become "gone" at all, because {@code restart: always} keeps bringing it
 * back. That is the predictive alert the issue asked for, and it needed no new channel to the machine.
 */
public enum ContainerStanding {

    RUNNING,
    UNHEALTHY,
    RESTARTING,
    GONE;

    /**
     * How {@code container} reads on one scrape, in the domain's order of precedence: a restart loop
     * before "not running" (a restarting container is not running either, and "gone" is the least useful
     * true thing to say about it), and not-running before an unhealthy check.
     *
     * <p>Empty when the reading is <b>inconclusive</b>: the health check has not concluded yet. That is
     * the minute after a container comes up, and counting it either way would be a verdict Vaier has not
     * taken — unknown is not "unhealthy", and it is not "well" either.
     */
    public static Optional<ContainerStanding> read(DockerService container) {
        if (container.isRestarting()) {
            return Optional.of(RESTARTING);
        }
        if (!container.isRunning()) {
            return Optional.of(GONE);
        }
        return switch (container.health()) {
            case UNHEALTHY -> Optional.of(UNHEALTHY);
            case STARTING -> Optional.empty();
            case HEALTHY, NONE -> Optional.of(RUNNING);
        };
    }

    /** Whether this standing is something an operator would want to hear about. */
    public boolean isTrouble() {
        return this != RUNNING;
    }

    /** Whether this is worse news than {@code other} — the only thing that earns a second email. */
    public boolean isWorseThan(ContainerStanding other) {
        return other == null || compareTo(other) > 0;
    }

    /**
     * The standing {@code name} spells, or {@link #RUNNING} when it spells nothing Vaier knows.
     *
     * <p>Tolerance errs quiet, as the file that holds these does: a memory written by a newer Vaier, or a
     * corrupted one, means re-learning what is running over the next 30 seconds. It must never become an
     * inbox full of containers nobody has evidence about.
     */
    public static ContainerStanding named(String name) {
        for (ContainerStanding standing : values()) {
            if (standing.name().equals(name)) {
                return standing;
            }
        }
        return RUNNING;
    }
}
