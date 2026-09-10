package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * <b>Spend</b> (#360): what Chat has cost on the operator's own Anthropic API key, counted by Vaier from the
 * tokens every answer used and priced at list price. Tokens are what is kept — a price is a fact about a
 * day, tokens are a fact about what happened — so a changed price re-prices the past honestly.
 */
class SpendTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Test
    void usageAddsUp_andNoneIsNothing() {
        ModelUsage a = new ModelUsage("claude-opus-5", 1000, 100, 200, 3000);
        ModelUsage b = new ModelUsage("claude-opus-5", 10, 1, 2, 3);

        assertThat(a.plus(b)).isEqualTo(new ModelUsage("claude-opus-5", 1010, 101, 202, 3003));
        assertThat(ModelUsage.none().plus(a)).isEqualTo(a);
        assertThat(ModelUsage.none().isNothing()).isTrue();
        assertThat(a.isNothing()).isFalse();
    }

    /** List price for the model Vaier pins, per million tokens: $5 in, $25 out, ×1.25 cache write, ×0.1 cache read. */
    @Test
    void theModelVaierUsesHasAPrice() {
        ClaudePrice price = ClaudePrice.forModel("claude-opus-5");

        assertThat(price.inputPerMillion()).isEqualTo(5.0);
        assertThat(price.outputPerMillion()).isEqualTo(25.0);
        assertThat(price.cacheWritePerMillion()).isEqualTo(6.25);
        assertThat(price.cacheReadPerMillion()).isEqualTo(0.5);
        // 1M in, 100k out, 200k cache write, 3M cache read: 5 + 2.5 + 1.25 + 1.5
        assertThat(price.costUsd(new ModelUsage("claude-opus-5", 1_000_000, 100_000, 200_000, 3_000_000)))
            .isCloseTo(10.25, within(0.000001));
    }

    @Test
    void aModelWithoutAPriceIsRefused_neverPricedAtNothing() {
        assertThatThrownBy(() -> ClaudePrice.forModel("claude-nonesuch"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("claude-nonesuch");
    }

    @Test
    void spendIsKeptPerMonth_asTokensAndCalls() {
        Spend spend = Spend.empty()
            .record(new ModelUsage("claude-opus-5", 1000, 100, 0, 0), SEPTEMBER)
            .record(new ModelUsage("claude-opus-5", 2000, 200, 0, 0), SEPTEMBER)
            .record(new ModelUsage("claude-opus-5", 5, 5, 0, 0), YearMonth.of(2026, 10));

        MonthSpend september = spend.month(SEPTEMBER);
        assertThat(september.calls()).isEqualTo(2);
        assertThat(september.usage()).isEqualTo(new ModelUsage("claude-opus-5", 3000, 300, 0, 0));
        assertThat(september.costUsd()).isCloseTo(0.0225, within(0.000001));
        assertThat(spend.month(YearMonth.of(2026, 8))).isEqualTo(MonthSpend.nothing(YearMonth.of(2026, 8)));
        assertThat(spend.months()).hasSize(2);
    }

    /** An answer that used no tokens — a refused key, a failed call — is not a call worth counting. */
    @Test
    void nothingUsedIsNotRecorded() {
        Spend spend = Spend.empty().record(ModelUsage.none(), SEPTEMBER);

        assertThat(spend.months()).isEmpty();
    }

    @Test
    void recordingLeavesTheSpendItCameFromAlone() {
        Spend before = Spend.empty();
        before.record(new ModelUsage("claude-opus-5", 1, 1, 0, 0), SEPTEMBER);

        assertThat(before.months()).isEmpty();
        assertThat(new Spend(List.of()).months()).isEmpty();
    }

    @Test
    void theFigureIsSaidInDollars_toTheCent_andShowsTheOddCentWhenThatIsAllThereIs() {
        assertThat(MonthSpend.nothing(SEPTEMBER).figure()).isEqualTo("$0.00");
        Spend spend = Spend.empty().record(new ModelUsage("claude-opus-5", 1_000_000, 100_000, 0, 0), SEPTEMBER);
        assertThat(spend.month(SEPTEMBER).figure()).isEqualTo("$7.50");
    }
}
