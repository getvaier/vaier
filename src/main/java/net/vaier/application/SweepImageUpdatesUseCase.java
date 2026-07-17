package net.vaier.application;

import net.vaier.domain.UpdateAvailability;

import java.util.Map;

/**
 * Ask every registry Vaier's containers came from whether it now serves a newer image for the tag each
 * container runs, and record the verdicts so the REST payloads carry them.
 *
 * <p>Driven by the scheduled update watcher — once a day, because registry manifest requests are rate-limited
 * (anonymous Docker Hub allows roughly 100 per six hours) and an image going stale is not a thing that needs
 * noticing within thirty seconds.
 */
public interface SweepImageUpdatesUseCase {

    /**
     * Sweep now and return each running container image's verdict, keyed by the image string as its host
     * reports it. Total: an unreachable registry yields {@link UpdateAvailability#UNKNOWN} for that image
     * rather than an exception or a false "up to date".
     */
    Map<String, UpdateAvailability> sweepImageUpdates();
}
