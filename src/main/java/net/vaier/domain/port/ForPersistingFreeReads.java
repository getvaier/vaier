package net.vaier.domain.port;

import net.vaier.domain.FreeReads;

import java.util.function.UnaryOperator;

/** Driven port for every published service's {@link FreeReads}. An absent store is the healthy state: none. */
public interface ForPersistingFreeReads {

    FreeReads read();

    /** Apply {@code change} to the current free reads and persist the result, as one step. */
    void update(UnaryOperator<FreeReads> change);
}
