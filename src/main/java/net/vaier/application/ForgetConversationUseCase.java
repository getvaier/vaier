package net.vaier.application;

import net.vaier.domain.Operator;

/** Start over: drop the operator's kept <b>Conversation</b> (#360 slice 3). */
public interface ForgetConversationUseCase {

    void forget(Operator operator);
}
