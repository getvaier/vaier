package net.vaier.domain;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Watches one answer for a <b>Phantom confirmation</b>: words saying a <b>Confirmation</b> (or, in an errand,
 * a <b>Mailed confirmation</b>) is waiting when no tool made one and none from before is still open for the
 * operator. Such an answer is corrected once, and if the correction makes nothing either, Vaier says so
 * itself. Tool calls arrive on the model's threads, hence the atomics.
 */
public final class ConfirmationWatch {

    private static final Pattern CARD = Pattern.compile("\\bcards?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD_WAITING =
        Pattern.compile("\\bup\\b|waiting|click(?!ed)|here it is|proposed", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAILED = Pattern.compile("\\bmailed\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIL_WAITING =
        Pattern.compile("\\byes\\b|approv|confirm", Pattern.CASE_INSENSITIVE);

    private final Pattern said;
    private final Pattern waiting;
    private final String correction;
    private final String note;
    private final boolean alreadyOpen;
    private final AtomicBoolean made = new AtomicBoolean();
    private final AtomicBoolean corrected = new AtomicBoolean();

    private ConfirmationWatch(Pattern said, Pattern waiting, String correction, String note, boolean alreadyOpen) {
        this.said = said;
        this.waiting = waiting;
        this.correction = correction;
        this.note = note;
        this.alreadyOpen = alreadyOpen;
    }

    /** For Chat, where a confirmation is a card: the operator's held proposals and bundles, open or not. */
    public static ConfirmationWatch overCards(List<ActionProposal> held, List<Bundle> offered, long nowEpochMs) {
        boolean open = held.stream().anyMatch(proposal -> !proposal.expired(nowEpochMs))
            || offered.stream().anyMatch(bundle -> !bundle.expired(nowEpochMs));
        return new ConfirmationWatch(CARD, CARD_WAITING,
            "You said a card is up, but you called no action tool in this answer. If you meant a card already in "
                + "front of the operator, say so plainly and do not make it again; otherwise call the action tool "
                + "now, or tell the operator there is no card.",
            "(Vaier: no card was made — ask again.)", open);
    }

    /** For an errand, where a confirmation is a mail. */
    public static ConfirmationWatch overMail(MailedConfirmations waiting, Operator operator, long nowEpochMs) {
        return new ConfirmationWatch(MAILED, MAIL_WAITING,
            "You said something was mailed for a yes, but you called no action tool in this run. If you meant a "
                + "mail already waiting for the operator, say so plainly and do not send it again; otherwise call "
                + "the action tool now, or say plainly in your report that nothing was mailed.",
            "(Vaier: no approval mail was sent — nothing is waiting for your yes.)",
            waiting.anyWaitingFor(operator, nowEpochMs));
    }

    /** The offers, with every one that can make a confirmation or a card noting when it did. */
    public List<ToolOffer> watching(List<ToolOffer> offers) {
        return offers.stream().map(this::watch).toList();
    }

    private ToolOffer watch(ToolOffer offer) {
        if (!(offer.tool() instanceof ChatAction) && offer.tool() != ChatTool.BUNDLE_FILES) {
            return offer;
        }
        return new ToolOffer(offer.tool(), arguments -> {
            String result = offer.read().apply(arguments);
            if (madeOne(result)) {
                made.set(true);
            }
            return result;
        });
    }

    private static boolean madeOne(String toolResult) {
        return toolResult != null && (toolResult.startsWith(ActionProposal.PROPOSED)
            || toolResult.startsWith(MailedConfirmation.MAILED) || toolResult.startsWith(Bundle.OFFERED));
    }

    public boolean claims(String answer) {
        return answer != null && said.matcher(answer).find() && waiting.matcher(answer).find();
    }

    /** True at most once per answer: a phantom gets one correction, never a loop. */
    public boolean needsCorrection(String answer) {
        return !made.get() && !alreadyOpen && claims(answer) && corrected.compareAndSet(false, true);
    }

    /** What the model is told when its answer was a phantom. */
    public String correction() {
        return correction;
    }

    /** Vaier's own line once the correction has run: empty when a confirmation was made, or one is open. */
    public String closingNote() {
        return made.get() || alreadyOpen ? "" : note;
    }
}
