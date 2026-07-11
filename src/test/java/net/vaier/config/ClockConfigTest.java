package net.vaier.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    /**
     * The nightly fleet-backup schedule fires when {@code clock.getZone()}'s hour matches the configured
     * hour, so the clock's zone <em>is</em> the zone an operator means when they pick "02:00". A clock
     * pinned to UTC made that hour silently mean UTC no matter what {@code TZ} the container was given —
     * a backup set for 02:00 ran at 04:00 in Europe/Oslo. The clock must follow the JVM's zone.
     */
    @Test
    void systemClockFollowsTheJvmDefaultZoneSoTheScheduleHourMeansLocalTime() {
        Clock clock = new ClockConfig().systemClock();

        assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
    }
}
