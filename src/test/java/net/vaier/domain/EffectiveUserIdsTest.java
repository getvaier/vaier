package net.vaier.domain;

import net.vaier.domain.port.ForHoldingEffectiveUserIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The effective user's uid and groups, read on the fleet's rounds in front of their {@code df}. */
class EffectiveUserIdsTest {

    private static final MachineId ID = TestMachineIds.of("Apalveien 5");

    private static CommandResult out(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:x");
    }

    @Test
    void theReadRidesAheadOfTheSweepsCommand_silently_andIsNotADfRow() {
        String combined = EffectiveUserIds.readAheadOf(RemoteDiskUsage.DF_COMMAND);

        assertThat(combined).contains("id -u").contains("id -G").contains("2>/dev/null")
            .endsWith(RemoteDiskUsage.DF_COMMAND);
        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList("nas",
            "VAIER-UID=1000\nVAIER-GIDS=1000 100\n"
                + "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/sda1 100000 50000 50000 50% /");
        assertThat(filesystems).singleElement().satisfies(fs -> assertThat(fs.mountPoint()).isEqualTo("/"));
    }

    @Test
    void readFrom_takesTheIdsTheMachineSaid_andOnlyAnAnswerActuallyGivenIsOne() {
        record Row(String why, String stdout, EffectiveUserIds expected) {}
        for (Row row : new Row[] {
            new Row("an ordinary user", "VAIER-UID=1000\nVAIER-GIDS=1000 27 100\n/dev/sda1 1 1 1 1% /",
                new EffectiveUserIds(1000, Set.of(1000, 27, 100))),
            new Row("root", "VAIER-UID=0\nVAIER-GIDS=0\n", new EffectiveUserIds(0, Set.of(0))),
            new Row("no id on the machine", "VAIER-UID=\nVAIER-GIDS=\n", null),
            new Row("groups not said", "VAIER-UID=1000\n", null),
            new Row("no markers", "Filesystem 1024-blocks Used Available Capacity Mounted on\n", null),
        }) {
            Optional<EffectiveUserIds> read = EffectiveUserIds.readFrom(out(row.stdout()));
            assertThat(read).as(row.why()).isEqualTo(Optional.ofNullable(row.expected()));
        }
        assertThat(EffectiveUserIds.readFrom(null)).isEmpty();
    }

    @Test
    void retain_keepsAnAnswer_andATripThatLearnedNothingErasesNothing() {
        ForHoldingEffectiveUserIds holder = mock(ForHoldingEffectiveUserIds.class);

        EffectiveUserIds.retain(ID, out("VAIER-UID=1000\nVAIER-GIDS=1000\n"), holder);
        EffectiveUserIds.retain(ID, out("/dev/sda1 1 1 1 1% /"), holder);

        verify(holder).record(ID, new EffectiveUserIds(1000, Set.of(1000)));
        verify(holder, times(1)).record(any(), any());
    }
}
