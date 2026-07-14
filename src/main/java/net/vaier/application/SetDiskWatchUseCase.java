package net.vaier.application;

/**
 * Set whether Vaier watches one filesystem on one machine, and at what threshold (#325).
 *
 * <p>The per-machine, per-filesystem knob that makes the fleet-wide disk alert usable: {@code /} at 88% is
 * normal on the NAS (the fixed-size DSM system partition) and would be an emergency on Apalveien 5. Give the
 * NAS's {@code /} its own threshold, or mute it — but do it deliberately, because the default is watched.
 */
public interface SetDiskWatchUseCase {

    /**
     * Watch or mute {@code mountPoint} on {@code machineName}, optionally at its own threshold.
     *
     * @param thresholdPercent this filesystem's own alert threshold (1–100), or null to use the global one
     */
    void setDiskWatch(String machineName, String mountPoint, boolean watched, Integer thresholdPercent);
}
