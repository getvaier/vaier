package net.vaier.application;

import net.vaier.domain.Errand;
import net.vaier.domain.Operator;

/**
 * Send Marvin on an <b>errand</b> (#360): something to do later, once or on a <b>rhythm</b>. The rhythm
 * arrives as the one string the model wrote; what that string may say is the domain's decision.
 */
public interface AddErrandUseCase {

    Errand add(Operator operator, String instruction, String rhythm);
}
