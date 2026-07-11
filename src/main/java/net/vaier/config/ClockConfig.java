package net.vaier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the system {@link Clock} so time-dependent components (e.g. the disk-fill forecast in
 * {@code RemoteDiskWatcher}) can be driven by an injectable, test-steppable clock rather than reading
 * {@code Instant.now()} directly.
 *
 * <p>The clock carries the JVM's default zone, not UTC. The nightly fleet-backup schedule compares the
 * clock's local hour against the operator's configured hour, so a UTC-pinned clock made "02:00" mean
 * 02:00 UTC however the container's {@code TZ} was set — a backup asked for at 02:00 fired at 04:00 in
 * Europe/Oslo. Set {@code TZ} on the container (see {@code VAIER_TZ} in docker-compose.yml) to choose
 * the zone the schedule hour is read in. Everything else on this clock uses instants, which are
 * zone-independent.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
