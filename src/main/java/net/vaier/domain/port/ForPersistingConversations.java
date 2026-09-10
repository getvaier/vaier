package net.vaier.domain.port;

import net.vaier.domain.Conversation;
import net.vaier.domain.Operator;

import java.util.Optional;

/** Driven port keeping each operator's <b>Conversation</b> the way everything else is kept: a file. */
public interface ForPersistingConversations {

    Optional<Conversation> load(Operator operator);

    void save(Conversation conversation);

    void forget(Operator operator);
}
