package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /** How long the operator must have said nothing before an errand's report starts a fresh thread. */
    public static final Duration QUIET = Duration.ofHours(3);

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
    public Conversation withExchange(String question, String answer, Instant at) {
        return with(ConversationTurn.said(Role.OPERATOR, question, at)).withAnswer(answer, at);
    }

    /** Vaier's answer alone, as a <b>follow-up</b> is kept; an empty one is no turn. */
    public Conversation withAnswer(String answer, Instant at) {
        return answer == null || answer.isBlank() ? this : with(ConversationTurn.said(Role.VAIER, answer, at));
    }

    /**
     * An errand's report: a fresh thread once the operator has been quiet for {@link #QUIET} (so daily reports
     * never stack up), otherwise the next turn of the thread they are in. A turn with no time counts as old.
     */
    public Conversation withErrandReport(ConversationTurn report, Instant now) {
        return quietAt(now) ? empty(operator).with(report) : with(report);
    }

    private boolean quietAt(Instant now) {
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = turns.get(i);
            if (turn.role() == Role.OPERATOR) {
                return turn.at() == null || !turn.at().plus(QUIET).isAfter(now);
            }
        }
        return true;
    }

    /** The instruction a <b>follow-up</b> is asked with, when the thread ends on Vaier's record of a yes. */
    public Optional<String> followUp() {
        if (turns.isEmpty()) {
            return Optional.empty();
        }
        ConversationTurn last = turns.get(turns.size() - 1);
        return last.card().filter(ConfirmationRecord::recordsAYes).map(yes -> ActionProposal.FOLLOW_UP);
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

    /**
     * The summary of {@code summarised}'s older turns in their place, and everything after them kept as said —
     * turns added while the summary was made included. A blank summary, or a thread that is no longer the one
     * summarised, changes nothing.
     */
    public Conversation compacted(String newSummary, Conversation summarised) {
        int older = Math.max(0, summarised.turns.size() - KEEP_VERBATIM);
        boolean same = Objects.equals(summary, summarised.summary) && turns.size() >= older
            && turns.subList(0, older).equals(summarised.turns.subList(0, older));
        if (newSummary == null || newSummary.isBlank() || !same) {
            return this;
        }
        return new Conversation(operator, newSummary, turns.subList(older, turns.size()));
    }
}
