package net.vaier.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Spend</b> (#360): what Chat has cost on the operator's own Anthropic API key, month by month, counted
 * by Vaier from the tokens every answer used. Kept as tokens and re-priced when read, so a changed list
 * price re-prices the past honestly rather than freezing an old figure.
 */
public record Spend(List<MonthSpend> months) {

    public Spend {
        months = months == null ? List.of() : List.copyOf(months);
    }

    public static Spend empty() {
        return new Spend(List.of());
    }

    /** One answer's usage, under its month. Nothing used is not a call worth counting. */
    public Spend record(ModelUsage usage, YearMonth month) {
        if (usage == null || usage.isNothing()) {
            return this;
        }
        List<MonthSpend> updated = new ArrayList<>();
        boolean found = false;
        for (MonthSpend kept : months) {
            if (kept.month().equals(month)) {
                updated.add(kept.plus(usage));
                found = true;
            } else {
                updated.add(kept);
            }
        }
        if (!found) {
            updated.add(MonthSpend.nothing(month).plus(usage));
        }
        return new Spend(updated);
    }

    public MonthSpend month(YearMonth month) {
        return months.stream().filter(kept -> kept.month().equals(month)).findFirst()
            .orElseGet(() -> MonthSpend.nothing(month));
    }
}
