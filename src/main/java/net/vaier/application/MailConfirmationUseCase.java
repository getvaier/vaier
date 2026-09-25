package net.vaier.application;

import net.vaier.domain.ChatAction;
import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.Operator;

import java.util.Map;

/**
 * Propose one <b>Chat action</b> by mail, as a <b>Mailed confirmation</b>: kept for a day, and its
 * <b>approval link</b> mailed to {@code operator}. Nothing runs here. Throws {@code IllegalArgumentException}
 * when it cannot be asked — the action lacks what it needs, the operator has no address, enough are already
 * waiting, or mail is not set up — and {@code MailNotSentException} when the mail server would not take it.
 */
public interface MailConfirmationUseCase {

    MailedConfirmation mail(Operator operator, ChatAction action, Map<String, String> arguments);
}
