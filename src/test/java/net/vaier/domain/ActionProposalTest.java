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
    void aProposalCarriesAnIdTheActionAndTheWordingTheOperatorWillRead() {
        Map<String, String> arguments = Map.of("machine", "Colina 27",
            "machineId", "c0355605-e5a0-419a-8943-fdc5ec209958");
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP, arguments, NOW);

        assertThat(proposal.id()).isNotBlank();
        assertThat(proposal.action()).isEqualTo(ChatAction.RUN_BACKUP);
        assertThat(proposal.wording()).isEqualTo(ChatAction.RUN_BACKUP.wording(arguments));
        assertThat(proposal.arguments()).containsEntry("machineId", "c0355605-e5a0-419a-8943-fdc5ec209958");
        assertThat(proposal.proposedAtEpochMs()).isEqualTo(NOW);
    }

    @Test
    void twoProposalsNeverShareAnId() {
        ActionProposal a = ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);
        ActionProposal b = ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);

        assertThat(a.id()).isNotEqualTo(b.id());
    }

    /** What the action needs must be there: a card that says "Back up  now." is a card nobody can judge. */
    @Test
    void aProposalMissingWhatTheActionNeedsIsRefusedInWords() {
        assertThatThrownBy(() -> ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which machine.");
        assertThatThrownBy(() -> ActionProposal.propose(ChatAction.LET_PHONE_IN, Map.of("code", " "), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which code.");
    }

    /** A body is optional: a DELETE has none, and the card says so rather than refusing. */
    @Test
    void anOptionalArgumentMayBeLeftOut() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.CALL_SERVICE, Map.of("service",
            "paperless on Apalveien 5", "method", "DELETE", "path", "/api/tags/7", "host", "paperless.example.com",
            "headline", "Delete tag 7 in paperless."), NOW);

        assertThat(proposal.wording().details())
            .isEqualTo(ServiceCall.proposed("DELETE", "/api/tags/7", null).details("paperless on Apalveien 5"));
    }

    @Test
    void aProposalLivesTenMinutes() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);

        assertThat(proposal.expired(NOW + ActionProposal.TTL.toMillis() - 1)).isFalse();
        assertThat(proposal.expired(NOW + ActionProposal.TTL.toMillis())).isTrue();
        assertThat(proposal.requireLive(NOW + 1)).isSameAs(proposal);
        assertThatThrownBy(() -> proposal.requireLive(NOW + ActionProposal.TTL.toMillis()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That card has expired; ask again.");
    }

    /** What Vaier remembers of a card, both parts of each wording, in the words the next question reads back. */
    @Test
    void whatBecameOfTheCardIsSaidInOneShape() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), NOW);
        String proposed = proposal.wording().headline() + " " + proposal.wording().details();

        assertThat(proposal.outcomeSentence(true, new ActionWording("Backing up.", "See the Backups pane.")))
            .isEqualTo("Card from an action tool: " + proposed + " (done: Backing up. See the Backups pane.)");
        assertThat(proposal.outcomeSentence(false, new ActionWording("Vaier could not do that.", null)))
            .isEqualTo("Card from an action tool: " + proposed + " (could not be done: Vaier could not do that.)");
        assertThat(proposal.declinedSentence())
            .isEqualTo("Card from an action tool: " + proposed + " (the operator declined)");
    }

    /** What the model is told: it proposed, and nothing happened. The one lie this must prevent is "done". */
    @Test
    void theToolResultSaysNothingHasHappened() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), NOW);

        assertThat(proposal.toolResult())
            .contains("Back up Colina 27 now.")
            .contains("Nothing has happened yet")
            .contains("do not say it is done");
    }
}
