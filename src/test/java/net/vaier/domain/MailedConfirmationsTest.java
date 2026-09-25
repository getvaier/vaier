package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every <b>Mailed confirmation</b> still waiting for a yes, and every decision about opening one. */
class MailedConfirmationsTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = MailedConfirmation.TTL.toMillis();
    private static final Operator GEIR = Operator.of("geir@example.com");
    private static final Operator OTHER = Operator.of("someone@else.com");

    private static MailedConfirmation.Minted minted(Operator operator, long at) {
        return MailedConfirmation.mint(
            ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), at), operator, at);
    }

    @Test
    void itOpensOnlyWithItsOwnToken_forItsOwnOperator_withinADay_andIsTakenOnce() {
        MailedConfirmation.Minted minted = minted(GEIR, NOW);
        MailedConfirmations held = MailedConfirmations.empty().with(minted.confirmation(), NOW);

        assertThat(held.open(minted.token(), GEIR, NOW + DAY - 1)).isEqualTo(minted.confirmation());

        record Row(String why, String token, Operator operator, long at) {}
        MailedConfirmations taken = held.without(minted.confirmation());
        for (Row row : new Row[] {
            new Row("another token", minted(GEIR, NOW).token(), GEIR, NOW),
            new Row("a token cut short", minted.token().substring(1), GEIR, NOW),
            new Row("another operator", minted.token(), OTHER, NOW),
            new Row("a day later", minted.token(), GEIR, NOW + DAY),
        }) {
            assertThatThrownBy(() -> held.open(row.token(), row.operator(), row.at())).as(row.why())
                .isInstanceOf(NotFoundException.class)
                .hasMessage(MailedConfirmations.GONE);
        }
        // Taken once: gone afterwards, in the same words, so the page says nothing about why.
        assertThatThrownBy(() -> taken.open(minted.token(), GEIR, NOW))
            .isInstanceOf(NotFoundException.class).hasMessage(MailedConfirmations.GONE);
    }

    /** A roof per operator, so a watch that finds ten things cannot mail ten links; expired ones are swept. */
    @Test
    void atMostAFewWaitPerOperator_andExpiredOnesAreSweptOut() {
        MailedConfirmations held = MailedConfirmations.empty();
        for (int i = 0; i < MailedConfirmations.MOST_WAITING; i++) {
            held = held.with(minted(GEIR, NOW).confirmation(), NOW);
        }
        MailedConfirmations full = held;

        assertThatThrownBy(() -> full.with(minted(GEIR, NOW).confirmation(), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already waiting for the operator's yes");
        assertThat(full.with(minted(OTHER, NOW).confirmation(), NOW).held())
            .hasSize(MailedConfirmations.MOST_WAITING + 1);
        assertThat(full.with(minted(GEIR, NOW + DAY).confirmation(), NOW + DAY).held()).hasSize(1);
    }
}
