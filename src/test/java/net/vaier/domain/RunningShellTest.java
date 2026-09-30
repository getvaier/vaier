package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunningShellTest {

    private static RunningShell shell(String paneId, long ageMinutes, long sinceAttachedMinutes) {
        return new RunningShell(paneId, "bash", Duration.ofMinutes(ageMinutes),
            Duration.ofMinutes(sinceAttachedMinutes), false);
    }

    private static RunningShell held(String paneId, long ageMinutes, long sinceAttachedMinutes) {
        return new RunningShell(paneId, "bash", Duration.ofMinutes(ageMinutes),
            Duration.ofMinutes(sinceAttachedMinutes), true);
    }

    @Test
    void returnTo_isTheMostRecentlyAttachedShell_theYoungestOnATie_andNoneWhenNothingRuns() {
        // The operator works from several devices; each browser's own remembered shell is not the one they left.
        record Row(String why, List<RunningShell> shells, String expected) {}
        for (Row row : List.of(
            new Row("most recently attached wins over a younger shell",
                List.of(shell("old", 9000, 5), shell("fresh", 60, 60)), "old"),
            new Row("a tie goes to the most recently created",
                List.of(shell("a", 100, 10), shell("b", 20, 10)), "b"),
            // tmux dates an attach from when it began, so a shell held for hours is still in use now
            new Row("a shell a window holds right now wins",
                List.of(held("held", 9000, 180), shell("left", 60, 5)), "held"),
            new Row("nothing running", List.of(), null))) {
            assertThat(RunningShell.returnTo(row.shells()).map(RunningShell::paneId).orElse(null))
                .as(row.why()).isEqualTo(row.expected());
        }
    }
}
