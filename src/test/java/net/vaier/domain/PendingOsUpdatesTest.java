package net.vaier.domain;

import net.vaier.domain.port.ForHoldingPendingOsUpdates;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRunningSshCommands;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOsUpdatesTest {

    private static final MachineId ID = TestMachineIds.of("Apalveien 5");

    private static CommandResult out(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:x");
    }

    @Test
    void readFrom_countsWhatAptSaid_andOnlyAVerdictActuallyTakenIsOne() {
        record Row(String why, String stdout, Integer total, Integer security) {}
        for (Row row : new Row[] {
            new Row("security among routine", """
                VAIER-UPTIME=12.5
                VAIER-APT-RC=0
                VAIER-UPGRADABLE=libssl3/jammy-updates,jammy-security
                VAIER-UPGRADABLE=openssl/jammy-updates,jammy-security
                VAIER-UPGRADABLE=tzdata/jammy-updates
                Filesystem 1024-blocks Used Available Capacity Mounted on
                """, 3, 2),
            new Row("routine only (Debian names its security suite the same way)", """
                VAIER-APT-RC=0
                VAIER-UPGRADABLE=curl/bookworm-updates
                """, 1, 0),
            new Row("apt answered and nothing is waiting", "VAIER-APT-RC=0\n", 0, 0),
            new Row("apt failed or timed out: not none", "VAIER-APT-RC=124\n", null, null),
            new Row("no apt at all (a Synology): not none", "VAIER-UPTIME=3\n", null, null),
        }) {
            Optional<PendingOsUpdates> read = PendingOsUpdates.readFrom(ID, out(row.stdout()));
            if (row.total() == null) {
                assertThat(read).as(row.why()).isEmpty();
            } else {
                assertThat(read).as(row.why()).hasValue(new PendingOsUpdates(ID, row.total(), row.security()));
            }
        }
    }

    @Test
    void theVerdictAndItsWords_andNothingIsSaidWhenNothingWaits() {
        PendingOsUpdates none = new PendingOsUpdates(ID, 0, 0);
        assertThat(none.verdict()).isEqualTo(PendingOsUpdates.Verdict.NONE);
        assertThat(none.sentence()).isEmpty();
        PendingOsUpdates routine = new PendingOsUpdates(ID, 4, 0);
        assertThat(routine.verdict()).isEqualTo(PendingOsUpdates.Verdict.ROUTINE);
        assertThat(routine.sentence()).hasValue("4 updates waiting · none urgent");
        assertThat(routine.chip()).as("routine updates never mark a card").isEmpty();
        PendingOsUpdates security = new PendingOsUpdates(ID, 5, 2);
        assertThat(security.verdict()).isEqualTo(PendingOsUpdates.Verdict.SECURITY);
        assertThat(security.sentence()).hasValue("2 security updates waiting · 5 in all");
        assertThat(security.chip()).hasValue("2 security updates");
        assertThat(new PendingOsUpdates(ID, 1, 1).sentence()).hasValue("1 security update waiting");
        assertThat(new PendingOsUpdates(ID, 1, 1).chip()).hasValue("1 security update");
    }

    @Test
    void theProbeRidesAheadOfTheSweep_neverUpdatesTheLists_andNeedsNoRoot() {
        String command = PendingOsUpdates.readAheadOf("df -P");
        assertThat(command).endsWith("; df -P").contains("apt list --upgradable").contains("timeout ");
        assertThat(command).doesNotContain("apt-get update").doesNotContain("apt update").doesNotContain("sudo");
    }

    @Test
    void reread_asksTheMachineAgainOnItsOwn_andKeepsTheAnswer() {
        SshTarget target = mock(SshTarget.class);
        ForRunningSshCommands ssh = mock(ForRunningSshCommands.class);
        ForHoldingPendingOsUpdates holder = mock(ForHoldingPendingOsUpdates.class);
        when(holder.record(any())).thenReturn(Optional.empty());
        when(ssh.run(target, PendingOsUpdates.READ)).thenReturn(out("VAIER-APT-RC=0\n"));

        PendingOsUpdates.reread(ID, target, ssh, holder, mock(ForPublishingEvents.class));

        verify(holder).record(new PendingOsUpdates(ID, 0, 0));
    }

    @Test
    void retain_keepsAReadingAndSpeaksOnlyOnAChange_andAReadingNotTakenErasesNothing() {
        ForHoldingPendingOsUpdates holder = mock(ForHoldingPendingOsUpdates.class);
        ForPublishingEvents events = mock(ForPublishingEvents.class);
        PendingOsUpdates two = new PendingOsUpdates(ID, 2, 0);
        when(holder.record(two)).thenReturn(Optional.empty(), Optional.of(two));

        PendingOsUpdates.retain(ID, out("VAIER-APT-RC=0\nVAIER-UPGRADABLE=a/x\nVAIER-UPGRADABLE=b/x\n"), holder, events);
        PendingOsUpdates.retain(ID, out("VAIER-APT-RC=0\nVAIER-UPGRADABLE=a/x\nVAIER-UPGRADABLE=b/x\n"), holder, events);
        PendingOsUpdates.retain(ID, out("nothing"), holder, events);

        verify(holder, times(2)).record(any());
        verify(events, times(1)).publish("vpn-peers", "os-updates-changed", "");
    }
}
