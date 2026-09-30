package net.vaier.domain;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A persistent shell found running on a machine: which pane it belongs to, what its active pane is running,
 * how old it is, how long since a window last held it, and whether one holds it right now.
 */
public record RunningShell(String paneId, String running, Duration age, Duration sinceAttached, boolean attached) {

    /** The shell to return to: the most recently used (one a window holds counts as now), the youngest on a tie. */
    public static Optional<RunningShell> returnTo(List<RunningShell> shells) {
        return shells.stream()
            .min(Comparator.comparing(RunningShell::sinceUsed).thenComparing(RunningShell::age));
    }

    private Duration sinceUsed() {
        return attached ? Duration.ZERO : sinceAttached;
    }
}
