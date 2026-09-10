package net.vaier.domain;

import java.time.YearMonth;
import java.util.Locale;

/** One month of <b>Spend</b>: the tokens, the calls, and the figure they come to at list price. */
public record MonthSpend(YearMonth month, ModelUsage usage, int calls) {

    public static MonthSpend nothing(YearMonth month) {
        return new MonthSpend(month, ModelUsage.none(), 0);
    }

    public MonthSpend plus(ModelUsage more) {
        return new MonthSpend(month, usage.plus(more), calls + 1);
    }

    public double costUsd() {
        return usage.isNothing() ? 0.0 : ClaudePrice.forModel(usage.model()).costUsd(usage);
    }

    /** Dollars to the cent, as the top bar shows it. */
    public String figure() {
        return String.format(Locale.ROOT, "$%.2f", costUsd());
    }
}
