package net.vaier.domain.port;

import net.vaier.domain.Conversation;
import net.vaier.domain.Operator;

import java.util.Optional;
import java.util.function.UnaryOperator;

/** Driven port keeping each operator's <b>Conversation</b> the way everything else is kept: a file. */
public interface ForPersistingConversations {

    Optional<Conversation> load(Operator operator);

    /**
     * Read, change and write the operator's conversation as one step, so no other writer's turn is lost in
     * between. The change is given an empty conversation when none is kept; the kept result is returned.
     */
    Conversation update(Operator operator, UnaryOperator<Conversation> change);

    void forget(Operator operator);
}
