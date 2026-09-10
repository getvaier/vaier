package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * A <b>Conversation</b> Vaier keeps (#360 slice 3): every turn under the operator who asked, and — once it
 * is long — the model's own summary standing in for the older turns, so the thread never outgrows what a
 * question can carry and a follow-up next week still knows what "and Colina?" means.
 *
 * <p>The decisions are here: when a thread is long enough to shorten, which turns go to the model to be
 * summarised, which are kept as said, and how the summary is put to the model — as Vaier's own words,
 * first, never as something the operator said.
 */
public record Conversation(Operator operator, String summary, List<ConversationTurn> turns) {

    /** Past this many turns the older ones are summarised; well under what a question may carry. */
    public static final int MAX_TURNS = 40;

    /** How many of the latest turns stay exactly as said after a compaction. */
    public static final int KEEP_VERBATIM = 12;

    private static final String IN_BRIEF = "Earlier in this conversation, in brief: ";

    public Conversation {
        if (operator == null) {
            throw new IllegalArgumentException("A conversation belongs to an operator");
        }
        turns = turns == null ? List.of() : List.copyOf(turns);
        summary = summary == null || summary.isBlank() ? null : summary.trim();
    }

    public static Conversation empty(Operator operator) {
        return new Conversation(operator, null, List.of());
    }

    public Conversation with(ConversationTurn turn) {
        List<ConversationTurn> more = new ArrayList<>(turns);
        more.add(turn);
        return new Conversation(operator, summary, more);
    }

    /** One exchange: the question always, the answer only if there was one — an empty answer is no turn. */
    public Conversation withExchange(String question, String answer) {
        Conversation asked = with(new ConversationTurn(Role.OPERATOR, question));
        return answer == null || answer.isBlank() ? asked : asked.with(new ConversationTurn(Role.VAIER, answer));
    }

    /** What the model is given before the question: the summary as Vaier's own words, then the turns. */
    public List<ConversationTurn> forModel() {
        List<ConversationTurn> history = new ArrayList<>();
        if (summary != null) {
            history.add(new ConversationTurn(Role.VAIER, IN_BRIEF + summary));
        }
        history.addAll(turns);
        return history;
    }

    public boolean needsCompaction() {
        return turns.size() > MAX_TURNS;
    }

    /** The turns to be summarised: an earlier summary too, so nothing is forgotten twice. */
    public List<ConversationTurn> olderTurns() {
        List<ConversationTurn> older = new ArrayList<>();
        if (summary != null) {
            older.add(new ConversationTurn(Role.VAIER, IN_BRIEF + summary));
        }
        older.addAll(turns.subList(0, Math.max(0, turns.size() - KEEP_VERBATIM)));
        return older;
    }

    /** The summary in place of the older turns, the last turns kept as said. A blank summary changes nothing. */
    public Conversation compacted(String newSummary) {
        if (newSummary == null || newSummary.isBlank()) {
            return this;
        }
        return new Conversation(operator, newSummary,
            turns.subList(Math.max(0, turns.size() - KEEP_VERBATIM), turns.size()));
    }
}
