package net.vaier.application;

import net.vaier.domain.Memory;

/** Keep one fact in Vaier's <b>Memory</b>, across conversations (#360). Refuses nothing, and an essay. */
public interface RememberUseCase {

    Memory.Fact remember(String fact);
}
