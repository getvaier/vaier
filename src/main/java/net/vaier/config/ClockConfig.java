package net.vaier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the system {@link Clock} so time-dependent components (e.g. the disk-fill forecast in
 * {@code RemoteDiskWatcher}) can be driven by an injectable, test-steppable clock rather than reading
 * {@code Instant.now()} directly.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
