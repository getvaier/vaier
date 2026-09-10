package net.vaier.application;

import net.vaier.domain.Operator;

/**
 * Keep what became of a <b>Confirmation</b> as a turn in the operator's <b>Conversation</b>, in Vaier's
 * voice, so the next question knows the backup was started or the card declined (#360 slice 3).
 */
public interface RememberActionOutcomeUseCase {

    void remember(Operator operator, String outcome);
}
