package net.vaier.application;

import net.vaier.domain.Conversation;
import net.vaier.domain.Operator;

/** The operator's kept <b>Conversation</b>, or an empty one when nothing has been asked yet (#360 slice 3). */
public interface GetConversationUseCase {

    Conversation get(Operator operator);
}
