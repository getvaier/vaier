package net.vaier.application;

import net.vaier.domain.Memory;

/** Everything Vaier remembers, for the pane that shows it (#360). */
public interface GetMemoryUseCase {

    Memory getMemory();
}
