package net.vaier.application;

import net.vaier.domain.Edition;

/**
 * Resolves the {@link Edition} this instance currently runs as, from the installed licence. The
 * web layer's Enterprise gate and any edition-aware UI depend on this rather than reading licence
 * state themselves.
 */
public interface GetEditionUseCase {

    Edition currentEdition();
}
