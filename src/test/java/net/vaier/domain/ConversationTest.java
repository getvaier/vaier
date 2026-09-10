package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Conversation</b> Vaier keeps (#360 slice 3): every turn, per operator, and — once it is long — the
 * model's own summary standing in for the older turns so the thread never outgrows what a question can
 * carry.
 */
class ConversationTest {

    private static final Operator GEIR = Operator.of("geir@example.com");

    private static Conversation ofLength(int turns) {
        Conversation c = Conversation.empty(GEIR);
        for (int i = 0; i < turns; i++) {
            c = c.with(new ConversationTurn(i % 2 == 0 ? Role.OPERATOR : Role.VAIER, "turn " + i));
        }
        return c;
    }

    @Test
    void anEmptyConversationBelongsToItsOperatorAndSaysNothing() {
        Conversation c = Conversation.empty(GEIR);

        assertThat(c.operator()).isEqualTo(GEIR);
        assertThat(c.turns()).isEmpty();
        assertThat(c.summary()).isNull();
        assertThat(c.forModel()).isEmpty();
        assertThat(c.needsCompaction()).isFalse();
    }

    @Test
    void aTurnIsAppendedInOrder_withoutChangingTheConversationItCameFrom() {
        Conversation before = Conversation.empty(GEIR);
        Conversation after = before.with(new ConversationTurn(Role.OPERATOR, "is the nas up?"))
            .with(new ConversationTurn(Role.VAIER, "yes."));

        assertThat(before.turns()).isEmpty();
        assertThat(after.turns()).extracting(ConversationTurn::text).containsExactly("is the nas up?", "yes.");
    }

    /** One exchange: the question always, the answer only if there was one — an empty answer is no turn. */
    @Test
    void anExchangeKeepsTheQuestion_andTheAnswerOnlyWhenThereWasOne() {
        Conversation c = Conversation.empty(GEIR);

        assertThat(c.withExchange("is the nas up?", "yes.").turns()).extracting(ConversationTurn::text)
            .containsExactly("is the nas up?", "yes.");
        assertThat(c.withExchange("anything?", "  ").turns()).extracting(ConversationTurn::text)
            .containsExactly("anything?");
        assertThat(c.withExchange("anything?", null).turns()).extracting(ConversationTurn::text)
            .containsExactly("anything?");
    }

    @Test
    void aConversationMustHaveAnOperator() {
        assertThatThrownBy(() -> new Conversation(null, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** What the model is given: the summary first, as Vaier's own words, then the turns kept verbatim. */
    @Test
    void forTheModel_theSummaryComesFirstAsVaiersOwnWords() {
        Conversation c = new Conversation(GEIR, "Colina 27 was red; its tunnel had not handshaken.",
            List.of(new ConversationTurn(Role.OPERATOR, "and now?")));

        assertThat(c.forModel()).containsExactly(
            new ConversationTurn(Role.VAIER, "Earlier in this conversation, in brief: "
                + "Colina 27 was red; its tunnel had not handshaken."),
            new ConversationTurn(Role.OPERATOR, "and now?"));
    }

    @Test
    void itAsksForCompactionOncePastFortyTurns() {
        assertThat(ofLength(Conversation.MAX_TURNS).needsCompaction()).isFalse();
        assertThat(ofLength(Conversation.MAX_TURNS + 1).needsCompaction()).isTrue();
    }

    /** The older turns go to the model to be summarised; the last twelve are kept exactly as said. */
    @Test
    void compactingKeepsTheLastTwelveTurnsVerbatimUnderTheNewSummary() {
        Conversation longOne = ofLength(Conversation.MAX_TURNS + 1);

        List<ConversationTurn> older = longOne.olderTurns();
        Conversation compacted = longOne.compacted("what was said before");

        assertThat(older).hasSize(Conversation.MAX_TURNS + 1 - Conversation.KEEP_VERBATIM);
        assertThat(older.get(0).text()).isEqualTo("turn 0");
        assertThat(compacted.summary()).isEqualTo("what was said before");
        assertThat(compacted.turns()).hasSize(Conversation.KEEP_VERBATIM);
        assertThat(compacted.turns().get(0).text()).isEqualTo("turn " + (Conversation.MAX_TURNS + 1 - Conversation.KEEP_VERBATIM));
        assertThat(compacted.needsCompaction()).isFalse();
    }

    /** A summary that already exists is part of what is summarised again, so nothing is forgotten twice. */
    @Test
    void anEarlierSummaryIsFoldedIntoTheNextOne() {
        Conversation c = new Conversation(GEIR, "the first summary", new ArrayList<>(ofLength(Conversation.MAX_TURNS + 1).turns()));

        assertThat(c.olderTurns().get(0)).isEqualTo(new ConversationTurn(Role.VAIER,
            "Earlier in this conversation, in brief: the first summary"));
    }

    @Test
    void aBlankSummaryLeavesTheConversationAsItWas() {
        Conversation longOne = ofLength(Conversation.MAX_TURNS + 1);

        assertThat(longOne.compacted("  ")).isSameAs(longOne);
    }
}
