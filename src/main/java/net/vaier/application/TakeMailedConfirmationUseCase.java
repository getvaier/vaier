package net.vaier.application;

import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.Operator;

/**
 * Take the <b>Mailed confirmation</b> an <b>approval link</b> opens, once, for the signed-in operator it was
 * mailed to. Throws {@code NotFoundException} when it is used, expired, or not theirs; nothing may run then.
 */
public interface TakeMailedConfirmationUseCase {

    MailedConfirmation take(String token, Operator operator);
}
