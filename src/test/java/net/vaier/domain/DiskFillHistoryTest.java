package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DiskFillHistoryTest {

    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");

    private static Instant hours(double h) {
        return T0.plusSeconds((long) (h * 3600));
    }

    @Test
    void underMinSamples_returnsEmpty() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 80);
        history.record(hours(1), 81);

        assertThat(history.forecast("nas")).isEmpty();
    }

    @Test
    void flatSeries_returnsEmpty_slopeNotPositive() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 50);
        history.record(hours(1), 50);
        history.record(hours(2), 50);

        assertThat(history.forecast("nas")).isEmpty();
    }

    @Test
    void decliningSeries_returnsEmpty_slopeNegative() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 60);
        history.record(hours(1), 55);
        history.record(hours(2), 50);

        assertThat(history.forecast("nas")).isEmpty();
    }

    @Test
    void spanTooShort_returnsEmpty() {
        // 3 samples but only 10 minutes of span (< MIN_SPAN 15 min) — too little signal to trust a trend.
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 80);
        history.record(hours(5.0 / 60.0), 81);
        history.record(hours(10.0 / 60.0), 82);

        assertThat(history.forecast("nas")).isEmpty();
    }

    @Test
    void steadyClimb_computesRunwayWithinTolerance() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 80);
        history.record(hours(1), 81);
        history.record(hours(2), 82);
        history.record(hours(3), 83);

        Optional<DiskFillForecast> forecast = history.forecast("nas");

        assertThat(forecast).isPresent();
        assertThat(forecast.get().machineName()).isEqualTo("nas");
        assertThat(forecast.get().currentPercent()).isEqualTo(83);
        assertThat(forecast.get().fillRatePercentPerHour()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        // (100 - 83) / 1%/h = 17h
        assertThat(forecast.get().runway().toHours()).isEqualTo(17);
    }

    @Test
    void noisyClimb_producesSanePositiveSlope() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 80);
        history.record(hours(1), 82);
        history.record(hours(2), 81);
        history.record(hours(3), 84);
        history.record(hours(4), 83);
        history.record(hours(5), 85);

        Optional<DiskFillForecast> forecast = history.forecast("nas");

        assertThat(forecast).isPresent();
        assertThat(forecast.get().fillRatePercentPerHour()).isBetween(0.5, 1.5);
        assertThat(forecast.get().runway()).isPositive();
    }

    @Test
    void unevenTimestamps_handled() {
        DiskFillHistory history = new DiskFillHistory();
        history.record(hours(0), 90);
        history.record(hours(0.5), 91);
        history.record(hours(2), 93);

        Optional<DiskFillForecast> forecast = history.forecast("nas");

        assertThat(forecast).isPresent();
        assertThat(forecast.get().fillRatePercentPerHour()).isGreaterThan(0.0);
    }

    @Test
    void ringBuffer_evictsOldestBeyondK() {
        DiskFillHistory history = new DiskFillHistory();
        // An old, misleading sample that — if retained — would flatten the slope.
        history.record(hours(0), 88);
        // 12 recent samples climbing at exactly 1%/h; these fill the K=12 buffer and evict the outlier.
        for (int i = 0; i < 12; i++) {
            history.record(hours(100 + i), 88 + i);
        }

        Optional<DiskFillForecast> forecast = history.forecast("nas");

        assertThat(forecast).isPresent();
        assertThat(forecast.get().currentPercent()).isEqualTo(99);
        // Only the recent 12 (slope 1%/h) count; the old outlier is gone.
        assertThat(forecast.get().fillRatePercentPerHour()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(forecast.get().runway().toHours()).isEqualTo(1);
    }

    @Test
    void climbingBelowThreshold_withShortRunway_warrants() {
        DiskFillHistory history = new DiskFillHistory();
        // current 80%, climbing 1%/h → 20h runway, under the 24h horizon and below the 85 level.
        history.record(hours(0), 77);
        history.record(hours(1), 78);
        history.record(hours(2), 79);
        history.record(hours(3), 80);

        Optional<DiskFillForecast> forecast = history.forecast("nas");

        assertThat(forecast).isPresent();
        assertThat(forecast.get().runway()).isLessThan(Duration.ofHours(24));
        assertThat(forecast.get().warrantsEarlyWarning(85)).isTrue();
    }
}
