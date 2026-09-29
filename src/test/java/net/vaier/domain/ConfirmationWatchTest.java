package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationWatchTest {

    private static final Operator GEIR = Operator.of("geir@example.com");
    private static final ActionProposal PROPOSAL =
        ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), 0);

    private static final Bundle BUNDLE =
        Bundle.offer(MachineId.of("0b8f4c2e-1d3a-4e5f-9a6b-7c8d9e0f1a2b"), "NAS", List.of("/a"), "photos", 0);
    
    record Row(String answer, boolean claims) {}

    private static ConfirmationWatch noCardOpen() {
        return ConfirmationWatch.overCards(List.of(), List.of(), 0);
    }

    private static ConfirmationWatch noMailWaiting() {
        return ConfirmationWatch.overMail(MailedConfirmations.empty(), GEIR, 0);
    }

    @Test
    void claimsACard_whenCardIsSaidBesideWordsOfWaiting_inAnyCase() {
        for (Row row : List.of(
            new Row("The card is up.", true),
            new Row("The CARD is waiting for your click.", true),
            new Row("Here it is — the card for the backup.", true),
            new Row("I've proposed it as a card.", true),
            new Row("Click the card to go ahead.", true),
            // A card already clicked is the past, not one waiting: said in every follow-up.
            new Row("You clicked the card, and the light is off.", false),
            // "up" inside "backup" is not a claim.
            new Row("Your backup card history looks fine.", false),
            new Row("Colina's disk is up to 91%.", false),
            new Row("", false))) {
            assertThat(noCardOpen().claims(row.answer())).as(row.answer()).isEqualTo(row.claims());
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
            assertThat(noMailWaiting().claims(row.answer())).as(row.answer()).isEqualTo(row.claims());
        }
    }

    @Test
    void aClaimWithNothingMade_isCorrectedOnce_andNotedWhenTheCorrectionMadeNothingEither() {
        ConfirmationWatch watch = noCardOpen();

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
            new ToolOffer(ChatTool.BUNDLE_FILES, arguments -> BUNDLE.toolResult()))) {
            ConfirmationWatch watch = noCardOpen();
            List<ToolOffer> watched = watch.watching(List.of(read, maker));

            assertThat(watched.get(0)).as("a read is handed through untouched").isSameAs(read);
            watched.get(1).read().apply(Map.of());

            assertThat(watch.needsCorrection("Here it is: the card is up.")).as(maker.tool().toolName()).isFalse();
            assertThat(watch.closingNote()).isEmpty();
        }
    }

    @Test
    void anActionThatWasRefused_madeNothing() {
        ConfirmationWatch watch = noCardOpen();
        watch.watching(List.of(new ToolOffer(ChatAction.LIFT_BLOCK, arguments -> "Say which address.")))
            .get(0).read().apply(Map.of());

        assertThat(watch.needsCorrection("The card is up.")).isTrue();
    }

    /**
     * A claim may mean a card from an earlier answer: while one is still open for this operator it is no
     * phantom, and a retry would only make it twice. One past its lifetime is gone, so the claim is again.
     */
    @Test
    void aClaimWhileAnEarlierConfirmationIsStillOpen_isNoPhantom() {
        MailedConfirmations mailed = MailedConfirmations.empty()
            .with(MailedConfirmation.mint(PROPOSAL, GEIR, 0).confirmation(), 0);
        String card = "The card above is waiting for your click.";
        String mail = "I mailed you the upgrade for a yes.";
        record Case(String label, ConfirmationWatch watch, String answer, boolean phantom) {}
        for (Case row : List.of(
            new Case("a card still open", ConfirmationWatch.overCards(List.of(PROPOSAL), List.of(), 0), card, false),
            new Case("a download card still open", ConfirmationWatch.overCards(List.of(), List.of(BUNDLE), 0), card,
                false),
            new Case("a card past its ten minutes",
                ConfirmationWatch.overCards(List.of(PROPOSAL), List.of(), ActionProposal.TTL.toMillis()), card, true),
            new Case("a download past its hour",
                ConfirmationWatch.overCards(List.of(), List.of(BUNDLE), Bundle.TTL.toMillis()), card, true),
            new Case("a mail still waiting", ConfirmationWatch.overMail(mailed, GEIR, 0), mail, false),
            new Case("a mail waiting for somebody else",
                ConfirmationWatch.overMail(mailed, Operator.of("ann@example.com"), 0), mail, true),
            new Case("a mail past its day",
                ConfirmationWatch.overMail(mailed, GEIR, MailedConfirmation.TTL.toMillis()), mail, true))) {
            assertThat(row.watch().needsCorrection(row.answer())).as(row.label()).isEqualTo(row.phantom());
            assertThat(row.watch().closingNote().isEmpty()).as(row.label() + ": the closing note")
                .isEqualTo(!row.phantom());
        }
    }
}
