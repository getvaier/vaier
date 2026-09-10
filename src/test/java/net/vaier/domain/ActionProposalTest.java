package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Confirmation</b> as Vaier holds it (#360 slice 2): one proposed action, waiting for the operator's
 * click, for ten minutes and once only.
 */
class ActionProposalTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void aProposalCarriesAnIdTheActionAndTheSentenceTheOperatorWillRead() {
        ActionProposal proposal = ActionProposal.propose(AskAction.RUN_BACKUP, Map.of("machine", "Colina 27",
            "machineId", "c0355605-e5a0-419a-8943-fdc5ec209958"), NOW);

        assertThat(proposal.id()).isNotBlank();
        assertThat(proposal.action()).isEqualTo(AskAction.RUN_BACKUP);
        assertThat(proposal.sentence()).isEqualTo("Back up Colina 27 now.");
        assertThat(proposal.arguments()).containsEntry("machineId", "c0355605-e5a0-419a-8943-fdc5ec209958");
        assertThat(proposal.proposedAtEpochMs()).isEqualTo(NOW);
    }

    @Test
    void twoProposalsNeverShareAnId() {
        ActionProposal a = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);
        ActionProposal b = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    /** What the action needs must be there: a card that says "Back up  now." is a card nobody can judge. */
    @Test
    void aProposalMissingWhatTheActionNeedsIsRefusedInWords() {
        assertThatThrownBy(() -> ActionProposal.propose(AskAction.RUN_BACKUP, Map.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which machine.");
        assertThatThrownBy(() -> ActionProposal.propose(AskAction.LET_PHONE_IN, Map.of("code", " "), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which code.");
    }

    @Test
    void aProposalLivesTenMinutes() {
        ActionProposal proposal = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);

        assertThat(proposal.expired(NOW + ActionProposal.TTL.toMillis() - 1)).isFalse();
        assertThat(proposal.expired(NOW + ActionProposal.TTL.toMillis())).isTrue();
        assertThat(proposal.requireLive(NOW + 1)).isSameAs(proposal);
        assertThatThrownBy(() -> proposal.requireLive(NOW + ActionProposal.TTL.toMillis()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That card has expired; ask again.");
    }

    /** What Vaier remembers of a card, in the words the next question will read back. */
    @Test
    void whatBecameOfTheCardIsSaidInOneShape() {
        ActionProposal proposal = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);

        assertThat(proposal.outcomeSentence(true, "Lifted the block on 203.0.113.9."))
            .isEqualTo("Proposed: Lift the block on 203.0.113.9. (done: Lifted the block on 203.0.113.9.)");
        assertThat(proposal.outcomeSentence(false, "Vaier could not do that."))
            .isEqualTo("Proposed: Lift the block on 203.0.113.9. (could not be done: Vaier could not do that.)");
        assertThat(proposal.declinedSentence())
            .isEqualTo("Proposed: Lift the block on 203.0.113.9. (the operator declined)");
    }

    /** What the model is told: it proposed, and nothing happened. The one lie this must prevent is "done". */
    @Test
    void theToolResultSaysNothingHasHappened() {
        ActionProposal proposal = ActionProposal.propose(AskAction.RUN_BACKUP, Map.of("machine", "Colina 27"), NOW);

        assertThat(proposal.toolResult())
            .contains("Back up Colina 27 now.")
            .contains("Nothing has happened yet")
            .contains("do not say it is done");
    }
}
