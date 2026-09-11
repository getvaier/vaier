package net.vaier.domain;

import java.util.Locale;

/**
 * What a container's own health check last said (#317), as Vaier reads it off the container listing.
 *
 * <p>Docker writes the verdict into the status line it already hands back for every container — "Up 2
 * weeks (healthy)", "Up 3 minutes (unhealthy)", "Up 10 seconds (health: starting)". So this costs nothing:
 * the 30-second fleet scrape that lists containers has the fact in hand, and no container is ever
 * inspected to learn it. An inspect per running container would be a round-trip per container per machine
 * every 30 seconds for a fact already on the wire.
 *
 * <p>{@link #NONE} is the common case and the quiet one — most images declare no health check at all, and
 * a container with nothing to report must never read as trouble. It is also where an unread status lands:
 * a daemon that words it differently leaves Vaier with no verdict, and no verdict is not a failing one.
 */
public enum ContainerHealth {

    /** No health check, or nothing Vaier could read. Not a verdict — and never treated as one. */
    NONE,
    /** The check has not concluded yet: the container has just come up. Transient by definition. */
    STARTING,
    /** The check passes. */
    HEALTHY,
    /** The check fails: the container is running and the thing inside it is not well. */
    UNHEALTHY;

    private static final String HEALTHY_MARK = "(healthy)";
    private static final String UNHEALTHY_MARK = "(unhealthy)";
    private static final String STARTING_MARK = "health: starting";

    /**
     * What {@code status} — the Docker listing's own status line — says about the container's health.
     *
     * <p>Unhealthy is matched before healthy for the obvious and unforgiving reason: "(unhealthy)"
     * contains "healthy", and getting that order wrong would report every failing check as a passing one.
     */
    public static ContainerHealth fromStatus(String status) {
        if (status == null || status.isBlank()) {
            return NONE;
        }
        String said = status.toLowerCase(Locale.ROOT);
        if (said.contains(UNHEALTHY_MARK)) {
            return UNHEALTHY;
        }
        if (said.contains(HEALTHY_MARK)) {
            return HEALTHY;
        }
        return said.contains(STARTING_MARK) ? STARTING : NONE;
    }
}
