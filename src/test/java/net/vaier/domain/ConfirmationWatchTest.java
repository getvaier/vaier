package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationWatchTest {

    private static final Operator GEIR = Operator.of("geir@example.com");
    private static final ActionProposal PROPOSAL =
        ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), 0);

    record Row(String answer, boolean claims) {}

    @Test
    void claimsACard_whenCardIsSaidBesideWordsOfWaiting_inAnyCase() {
        for (Row row : List.of(
            new Row("The card is up.", true),
            new Row("The CARD is waiting for your click.", true),
            new Row("Here it is — the card for the backup.", true),
            new Row("I've proposed it as a card.", true),
            new Row("Click the card to go ahead.", true),
            // "up" inside "backup" is not a claim.
            new Row("Your backup card history looks fine.", false),
            new Row("Colina's disk is up to 91%.", false),
            new Row("", false))) {
            assertThat(ConfirmationWatch.overCards().claims(row.answer())).as(row.answer()).isEqualTo(row.claims());
        }
    }

    @Test
    void claimsAMail_whenMailedIsSaidBesideAYesOrAnApproval_inAnyCase() {
        for (Row row : List.of(
            new Row("I mailed you the upgrade for a yes.", true),
            new Row("Mailed for your approval: lift the block.", true),
            new Row("It is MAILED and waiting for you to confirm.", true),
            new Row("Backups ran; nothing needs you.", false),
            new Row("The card is up.", false))) {
            assertThat(ConfirmationWatch.overMail().claims(row.answer())).as(row.answer()).isEqualTo(row.claims());
        }
    }

    @Test
    void aClaimWithNothingMade_isCorrectedOnce_andNotedWhenTheCorrectionMadeNothingEither() {
        ConfirmationWatch watch = ConfirmationWatch.overCards();

        assertThat(watch.needsCorrection("The card is up.")).isTrue();
        assertThat(watch.needsCorrection("The card is up.")).as("never a second retry").isFalse();
        assertThat(watch.closingNote()).isEqualTo("(Vaier: no card was made — ask again.)");
    }

    /** Every real card counts: a proposal, a mailed confirmation, a bundle's download card. */
    @Test
    void aClaimBackedByAToolThatMadeOne_isNoPhantom() {
        ToolOffer read = new ToolOffer(ChatTool.FLEET, () -> "colina27 connected");
        for (ToolOffer maker : List.of(
            new ToolOffer(ChatAction.LIFT_BLOCK, arguments -> PROPOSAL.toolResult()),
            new ToolOffer(ChatAction.LIFT_BLOCK,
                arguments -> MailedConfirmation.mint(PROPOSAL, GEIR, 0).confirmation().toolResult()),
            new ToolOffer(ChatTool.BUNDLE_FILES, arguments -> Bundle.offer(MachineId.of("0b8f4c2e-1d3a-4e5f-9a6b-7c8d9e0f1a2b"), "NAS",
                List.of("/a"), "photos", 0).toolResult()))) {
            ConfirmationWatch watch = ConfirmationWatch.overCards();
            List<ToolOffer> watched = watch.watching(List.of(read, maker));

            assertThat(watched.get(0)).as("a read is handed through untouched").isSameAs(read);
            watched.get(1).read().apply(Map.of());

            assertThat(watch.needsCorrection("Here it is: the card is up.")).as(maker.tool().toolName()).isFalse();
            assertThat(watch.closingNote()).isEmpty();
        }
    }

    @Test
    void anActionThatWasRefused_madeNothing() {
        ConfirmationWatch watch = ConfirmationWatch.overCards();
        watch.watching(List.of(new ToolOffer(ChatAction.LIFT_BLOCK, arguments -> "Say which address.")))
            .get(0).read().apply(Map.of());

        assertThat(watch.needsCorrection("The card is up.")).isTrue();
    }
}
