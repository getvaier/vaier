package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A <b>Conversation</b> Vaier keeps (#360 slice 3): every turn, per operator, and — once it is long — the
 * model's own summary standing in for the older turns so the thread never outgrows what a question can
 * carry.
 */
class ConversationTest {

    private static final Operator GEIR = Operator.of("geir@example.com");
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");

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

        assertThat(c.withExchange("is the nas up?", "yes.", NOW).turns()).extracting(ConversationTurn::text)
            .containsExactly("is the nas up?", "yes.");
        assertThat(c.withExchange("anything?", "  ", NOW).turns()).extracting(ConversationTurn::text)
            .containsExactly("anything?");
        assertThat(c.withExchange("anything?", null, NOW).turns()).extracting(ConversationTurn::text)
            .containsExactly("anything?");
        assertThat(c.withExchange("is the nas up?", "yes.", NOW).turns()).extracting(ConversationTurn::at)
            .as("a turn kept now says when").containsExactly(NOW, NOW);
    }

    /**
     * A <b>follow-up</b> is owed only when the thread ends on Vaier's record of a yes: not after "Not now", not
     * after Marvin has already spoken, and never twice.
     */
    @Test
    void aFollowUpIsOwedOnlyWhenTheThreadEndsOnAYesToACard() {
        ActionProposal card = ActionProposal.propose(ChatAction.RUN_BACKUP, Map.of("machine", "Colina 27"), 0);
        ConversationTurn yes = ConversationTurn.recording(
            card.record(true, new ActionWording("Backing up.", null), null), NOW);
        record Row(String why, Conversation conversation, boolean owed) {}
        Conversation empty = Conversation.empty(GEIR);
        for (Row row : new Row[] {
            new Row("a yes that ran", empty.with(yes), true),
            new Row("a yes that failed", empty.with(ConversationTurn.recording(
                card.record(false, new ActionWording("Vaier could not do that.", null), null), NOW)), true),
            new Row("a yes kept as text before records had a shape", empty.with(new ConversationTurn(Role.VAIER,
                "Card from an action tool: Back up Colina 27 now. (done: Backing up.)")), true),
            new Row("Not now", empty.with(ConversationTurn.recording(card.declined(), NOW)), false),
            new Row("already followed up", empty.with(yes).withAnswer("It is off.", NOW), false),
            new Row("a question last", empty.with(new ConversationTurn(Role.OPERATOR, yes.text())), false),
            new Row("nothing at all", empty, false),
        }) {
            assertThat(row.conversation().followUp()).as(row.why())
                .isEqualTo(row.owed() ? Optional.of(ActionProposal.FOLLOW_UP) : Optional.empty());
        }
        assertThat(empty.withAnswer("  ", NOW).turns()).as("a silent follow-up is no turn").isEmpty();
    }

    /**
     * An errand's report starts a fresh thread only once the operator has been quiet for three hours; while
     * they are talking it joins the thread they are in. A turn kept before turns said when counts as old.
     */
    @Test
    void anErrandReportStartsAFreshThreadOnlyAfterThreeQuietHours() {
        ConversationTurn report = new ConversationTurn(Role.VAIER, "Errand, Every day at 08:00: all well.", NOW, null);
        Conversation earlier = new Conversation(GEIR, "what was said long ago", List.of());
        record Row(String why, Conversation before, boolean fresh) {}
        for (Row row : new Row[] {
            new Row("nothing said at all", earlier, true),
            new Row("only Vaier spoke", earlier.with(new ConversationTurn(Role.VAIER, "hi", NOW.minusSeconds(60), null)), true),
            new Row("asked two hours ago", earlier.with(new ConversationTurn(Role.OPERATOR, "is colina up?",
                NOW.minus(Duration.ofHours(2)), null)), false),
            new Row("asked three hours ago", earlier.with(new ConversationTurn(Role.OPERATOR, "is colina up?",
                NOW.minus(Conversation.QUIET), null)), true),
            new Row("asked before turns said when", earlier.with(new ConversationTurn(Role.OPERATOR, "is colina up?")), true),
        }) {
            Conversation after = row.before().withErrandReport(report, NOW);
            if (row.fresh()) {
                assertThat(after).as(row.why()).isEqualTo(Conversation.empty(GEIR).with(report));
            } else {
                assertThat(after).as(row.why()).isEqualTo(row.before().with(report));
            }
        }
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
        Conversation compacted = longOne.compacted("what was said before", longOne);

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

        assertThat(longOne.compacted("  ", longOne)).isSameAs(longOne);
    }

    /**
     * Summarising takes a model call, and the thread moves on meanwhile. What was said in the meantime stays;
     * a thread that was started over in the meantime is left as it is, since the summary is of another one.
     */
    @Test
    void compactingKeepsWhatWasSaidWhileTheSummaryWasMade() {
        Conversation summarised = ofLength(Conversation.MAX_TURNS + 1);
        ConversationTurn meanwhile = new ConversationTurn(Role.VAIER, "Errand, Every day at 08:00: all well.");

        Conversation compacted = summarised.with(meanwhile).compacted("the gist", summarised);

        assertThat(compacted.summary()).isEqualTo("the gist");
        assertThat(compacted.turns()).hasSize(Conversation.KEEP_VERBATIM + 1).endsWith(meanwhile);
        Conversation startedOver = Conversation.empty(GEIR).with(meanwhile);
        assertThat(startedOver.compacted("the gist", summarised)).isSameAs(startedOver);
    }
}
