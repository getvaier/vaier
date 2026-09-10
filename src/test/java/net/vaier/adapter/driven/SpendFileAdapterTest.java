package net.vaier.adapter.driven;

import net.vaier.domain.ModelUsage;
import net.vaier.domain.Spend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/** Spend is one YAML file beside everything else Vaier keeps: tokens per month, never a price. */
class SpendFileAdapterTest {

    @TempDir
    Path configDir;

    private SpendFileAdapter adapter() {
        return new SpendFileAdapter(configDir.toString());
    }

    @Test
    void nothingKeptYetIsNoSpend() {
        assertThat(adapter().load().months()).isEmpty();
    }

    @Test
    void spendComesBackExactlyAsSaved() throws Exception {
        Spend saved = Spend.empty()
            .record(new ModelUsage("claude-opus-5", 1000, 100, 200, 3000), YearMonth.of(2026, 9))
            .record(new ModelUsage("claude-opus-5", 5, 5, 0, 0), YearMonth.of(2026, 10));
        adapter().save(saved);

        assertThat(adapter().load()).isEqualTo(saved);
        assertThat(Files.readString(configDir.resolve("spend.yml"))).doesNotContain("usd").doesNotContain("$");
    }

    @Test
    void aDamagedFileReadsAsNoSpend() throws Exception {
        Files.writeString(configDir.resolve("spend.yml"), "months: [ {month: x} ]\n: : :\n");

        assertThat(adapter().load().months()).isEmpty();
    }
}
