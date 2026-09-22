package net.vaier.domain;

import java.time.Duration;

/**
 * A persistent shell found running on a machine: which pane it belongs to, what its active pane is running,
 * how old it is, how long since a window last held it, and whether one holds it right now.
 */
public record RunningShell(String paneId, String running, Duration age, Duration sinceAttached, boolean attached) {
}
