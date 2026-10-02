package net.vaier.application;

import net.vaier.domain.PendingOsUpdates;

import java.util.List;

/**
 * Each machine's pending OS updates, as the five-minute sweep last read them. Memory only — it wakes nothing.
 * A machine absent from the list has not been read, which is never the same as nothing waiting.
 */
public interface GetPendingOsUpdatesUseCase {

    List<PendingOsUpdates> getPendingOsUpdates();
}
