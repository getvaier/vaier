package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One <b>Chat tool</b> actually wired to a read Vaier can perform (#360). */
class ToolOfferTest {

    @Test
    void anOfferCarriesTheToolItAnswersForAndTheReadThatAnswersIt() {
        ToolOffer offer = new ToolOffer(ChatTool.DISKS, () -> "colina27 /volume1 86%");

        assertThat(offer.tool()).isEqualTo(ChatTool.DISKS);
        assertThat(offer.read().apply(Map.of())).isEqualTo("colina27 /volume1 86%");
    }

    /** The command run is answered from what the model said: which machine, what to run. */
    @Test
    void anOfferMayReadFromTheModelsArguments() {
        ToolOffer offer = new ToolOffer(ChatTool.RUN_ON_MACHINE,
            args -> args.get("machine") + ": " + args.get("command"));

        assertThat(offer.read().apply(Map.of("machine", "Colina 27", "command", "uptime")))
            .isEqualTo("Colina 27: uptime");
    }

    /** An action is offered through the same shape; what it does when called is the offerer's business. */
    @Test
    void anActionMayBeOfferedLikeARead() {
        ToolOffer offer = new ToolOffer(ChatAction.RUN_BACKUP, args -> "proposed");

        assertThat(offer.tool()).isEqualTo(ChatAction.RUN_BACKUP);
        assertThat(offer.read().apply(Map.of("machine", "Colina 27"))).isEqualTo("proposed");
    }

    /** A tool with nothing behind it would be offered to the model and then fail when it was called. */
    @Test
    void anOfferMustCarryBoth() {
        assertThatThrownBy(() -> new ToolOffer(null, () -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolOffer(ChatTool.DISKS, (Supplier<String>) null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolOffer(ChatTool.DISKS, (Function<Map<String, String>, String>) null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
