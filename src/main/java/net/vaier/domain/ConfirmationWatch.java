package net.vaier.domain;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Watches one answer for a <b>Phantom confirmation</b>: words saying a <b>Confirmation</b> (or, in an errand,
 * a <b>Mailed confirmation</b>) is waiting when no tool made one. Such an answer is corrected once, and if
 * the correction makes nothing either, Vaier says so itself. Tool calls arrive on the model's threads, hence
 * the atomics.
 */
public final class ConfirmationWatch {

    private static final Pattern CARD = Pattern.compile("\\bcards?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CARD_WAITING =
        Pattern.compile("\\bup\\b|waiting|click|here it is|proposed", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAILED = Pattern.compile("\\bmailed\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAIL_WAITING =
        Pattern.compile("\\byes\\b|approv|confirm", Pattern.CASE_INSENSITIVE);

    private final Pattern said;
    private final Pattern waiting;
    private final String correction;
    private final String note;
    private final AtomicBoolean made = new AtomicBoolean();
    private final AtomicBoolean corrected = new AtomicBoolean();

    private ConfirmationWatch(Pattern said, Pattern waiting, String correction, String note) {
        this.said = said;
        this.waiting = waiting;
        this.correction = correction;
        this.note = note;
    }

    /** For Chat, where a confirmation is a card. */
    public static ConfirmationWatch overCards() {
        return new ConfirmationWatch(CARD, CARD_WAITING,
            "You said a card is up, but you called no action tool, so no card exists. Call the action tool now, "
                + "or tell the operator plainly that there is no card.",
            "(Vaier: no card was made — ask again.)");
    }

    /** For an errand, where a confirmation is a mail. */
    public static ConfirmationWatch overMail() {
        return new ConfirmationWatch(MAILED, MAIL_WAITING,
            "You said something was mailed for a yes, but you called no action tool, so no mail went. Call the "
                + "action tool now, or say plainly in your report that nothing was mailed.",
            "(Vaier: no approval mail was sent — nothing is waiting for your yes.)");
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
        return !made.get() && claims(answer) && corrected.compareAndSet(false, true);
    }

    /** What the model is told when its answer was a phantom. */
    public String correction() {
        return correction;
    }

    /** Vaier's own line once the correction has run: empty when a confirmation was made after all. */
    public String closingNote() {
        return made.get() ? "" : note;
    }
}
