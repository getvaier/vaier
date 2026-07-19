package net.vaier.application;

import net.vaier.domain.ScopedImage;
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
     * Sweep now and return each running container's verdict, keyed by the {@link ScopedImage} it is — the
     * image string <b>and</b> the machine it runs on, so the same tag on two hosts is two verdicts. Total: an
     * unreachable registry yields {@link UpdateAvailability#UNKNOWN} for that image rather than an exception or
     * a false "up to date".
     */
    Map<ScopedImage, UpdateAvailability> sweepImageUpdates();
}
