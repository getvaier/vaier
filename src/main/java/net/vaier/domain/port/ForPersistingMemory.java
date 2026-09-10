package net.vaier.domain.port;

import net.vaier.domain.Memory;

/** Driven port keeping Vaier's <b>Memory</b> the way everything else is kept: one file. */
public interface ForPersistingMemory {

    Memory load();

    void save(Memory memory);
}
