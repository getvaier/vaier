package net.vaier.domain.port;

import net.vaier.domain.Errands;

/** Driven port keeping every <b>errand</b> the way everything else is kept: one file. */
public interface ForPersistingErrands {

    Errands load();

    void save(Errands errands);
}
