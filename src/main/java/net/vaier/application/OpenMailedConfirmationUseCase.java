package net.vaier.application;

import net.vaier.domain.MailedConfirmation;
import net.vaier.domain.Operator;

/**
 * Look at the <b>Mailed confirmation</b> an <b>approval link</b> opens for the signed-in operator, without
 * taking it. Throws {@code NotFoundException} when it is used, expired, or not theirs.
 */
public interface OpenMailedConfirmationUseCase {

    MailedConfirmation open(String token, Operator operator);
}
