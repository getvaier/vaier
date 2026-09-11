package net.vaier.application;

import net.vaier.domain.Errand;

import java.util.List;

/** Every operator's <b>errands</b> that have come round, for the scheduler that runs them (#360). */
public interface GetDueErrandsUseCase {

    List<Errand> due();
}
