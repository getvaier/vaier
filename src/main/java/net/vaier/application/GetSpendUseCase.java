package net.vaier.application;

import net.vaier.domain.MonthSpend;

/** What Chat has cost this month, for the figure in the top bar (#360). */
public interface GetSpendUseCase {

    MonthSpend thisMonth();
}
