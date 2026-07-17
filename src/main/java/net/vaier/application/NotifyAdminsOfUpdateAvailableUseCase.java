package net.vaier.application;

import net.vaier.domain.ImageUpdateRollup;

/**
 * Tell admins that images running in the fleet have an update available. Driven by the scheduled update
 * watcher, which raises it only on an edge — an image that has <em>just</em> become out of date — so a stale
 * image is reported once and not every day until it is pulled.
 */
public interface NotifyAdminsOfUpdateAvailableUseCase {

    /** Mail every admin one rollup for the images in {@code rollup}. One sweep is one mail, never one per image. */
    void notifyAdminsOfUpdateAvailable(ImageUpdateRollup rollup);
}
