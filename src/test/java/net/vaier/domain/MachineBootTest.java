package net.vaier.domain;

import net.vaier.adapter.driven.InMemoryContainerStandingAdapter;
import net.vaier.domain.port.ForPersistingContainerStandings;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a machine last booted (#356) — the one fact that makes a missing container legible, read off
 * {@code /proc/uptime} on the sweep that is already there.
 */
class MachineBootTest {

    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien");
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    /** The sweep's output, with the uptime marker riding in front of whatever else it printed. */
    private static CommandResult said(String uptimeLine) {
        return new CommandResult(0, "VAIER-UPTIME=" + uptimeLine + "\nFilesystem 1024-blocks Used\n",
            "", false, null);
    }

    @Test
    void anUptimeInSecondsIsABootInstant() {
        assertThat(MachineBoot.readFrom(said("3600.42"), NOW))
            .contains(new MachineBoot(NOW.minusSeconds(3600)));
    }

    @Test
    void theReadRidesInFrontOfTheSweepsOwnCommand() {
        // One sign-in, three questions. The marker cannot parse as a df row, so the reading it travels
        // with is unchanged.
        assertThat(MachineBoot.readAheadOf("df -P")).contains("/proc/uptime").endsWith("df -P");
    }

    @Test
    void aTripThatCameBackWithoutTheMarkerSaysNothingAtAll() {
        // Unknown is not a boot instant. Inventing one from a failed read would put a fictional reboot
        // into an email as though it were the explanation.
        assertThat(MachineBoot.readFrom(new CommandResult(127, "", "not found", false, null), NOW))
            .isEmpty();
        assertThat(MachineBoot.readFrom(new CommandResult(-1, "", "", true, null), NOW)).isEmpty();
        // A machine with no readable /proc/uptime prints an empty marker.
        assertThat(MachineBoot.readFrom(said(""), NOW)).isEmpty();
        assertThat(MachineBoot.readFrom(said("not a number"), NOW)).isEmpty();
    }

    @Test
    void whatWasReadIsRemembered_andWhatWasNotIsNot() {
        ForPersistingContainerStandings standings = new InMemoryContainerStandingAdapter();

        MachineBoot.retain(APALVEIEN, said("600.0"), standings, NOW);
        assertThat(standings.bootOf(APALVEIEN)).contains(NOW.minusSeconds(600));

        MachineBoot.retain(APALVEIEN, said("nonsense"), standings, NOW);
        // Still the last good reading: a failed read never overwrites what Vaier actually learned.
        assertThat(standings.bootOf(APALVEIEN)).contains(NOW.minusSeconds(600));
    }
}
