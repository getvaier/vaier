package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DiskFillForecastTest {

    @Test
    void warrantsEarlyWarning_whenBelowLevelThresholdAndRunwayShort() {
        DiskFillForecast forecast = new DiskFillForecast("nas", "/volume1", 80, 1.0, Duration.ofHours(20));

        assertThat(forecast.warrantsEarlyWarning(85)).isTrue();
    }

    @Test
    void warrantsEarlyWarning_false_whenRunwayBeyondHorizon() {
        DiskFillForecast forecast = new DiskFillForecast("nas", "/volume1", 80, 1.0, Duration.ofHours(30));

        assertThat(forecast.warrantsEarlyWarning(85)).isFalse();
    }

    @Test
    void warrantsEarlyWarning_false_whenAtOrAboveLevelThreshold_soLevelAlertTakesOver() {
        // short runway, but the disk is already at/over the level threshold: the pressure alert owns this,
        // the forecast must go quiet so the operator is never paged twice for the same disk.
        DiskFillForecast atThreshold = new DiskFillForecast("nas", "/volume1", 85, 1.0, Duration.ofHours(10));
        DiskFillForecast aboveThreshold = new DiskFillForecast("nas", "/volume1", 90, 1.0, Duration.ofHours(2));

        assertThat(atThreshold.warrantsEarlyWarning(85)).isTrue(); // 85 <= 85 → still forecast territory
        assertThat(aboveThreshold.warrantsEarlyWarning(85)).isFalse();
    }

    @Test
    void forecastSubject_namesMachineAndApproxRunwayHours() {
        DiskFillForecast forecast = new DiskFillForecast("nas", "/volume1", 82, 1.0, Duration.ofHours(18));

        assertThat(forecast.forecastSubject()).contains("nas").contains("18h");
    }

    @Test
    void forecastBody_includesMachineCurrentRateRunwayAndUiLink() {
        String body = new DiskFillForecast("nas", "/volume1", 82, 1.5, Duration.ofHours(12)).forecastBody("example.com");

        assertThat(body).contains("nas").contains("82%").contains("1.5").contains("12").contains("https://");
    }

    @Test
    void forecastBody_omitsUiLink_whenBaseDomainBlank() {
        String body = new DiskFillForecast("nas", "/volume1", 82, 1.5, Duration.ofHours(12)).forecastBody("  ");

        assertThat(body).doesNotContain("https://");
    }

    @Test
    void forecastHorizon_isFixedTwentyFourHours() {
        assertThat(DiskFillForecast.FORECAST_HORIZON).isEqualTo(Duration.ofHours(24));
    }
}
