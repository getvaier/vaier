package net.vaier.domain.port;

import net.vaier.domain.Spend;

/** Driven port keeping <b>Spend</b> the way everything else is kept: one file, tokens per month. */
public interface ForPersistingSpend {

    Spend load();

    void save(Spend spend);
}
