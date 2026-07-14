package net.vaier.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-filesystem remote-disk-pressure state, so the {@code RemoteDiskWatcher} alerts only when a filesystem
 * crosses a boundary — not on every poll. It is the multi-filesystem sibling of {@link DiskPressureTracker}
 * (which tracks a single disk): each filesystem gets its own {@link DiskPressureTracker}, so the first
 * observation for a filesystem is a baseline and restarts never produce noise, and one filesystem's
 * transition never disturbs another's.
 *
 * <p><b>#325: keyed on machine AND mount point.</b> It used to be keyed on the machine alone, which was
 * defensible only while Vaier read {@code df -P /} and a machine therefore had exactly one disk. It is not
 * defensible now: on the NAS, {@code /} sits permanently above the threshold (it is the 2.3 GB DSM system
 * partition, 88% by design), so a machine-keyed tracker would already be "in pressure" and {@code /volume1}
 * crossing into pressure would be swallowed as "no change" — the second disk would never be heard. Which is
 * the very silence this issue is about.
 */
public class RemoteDiskPressureTracker {

    private final Map<String, DiskPressureTracker> perFilesystem = new ConcurrentHashMap<>();

    /**
     * Record {@code aboveThreshold} for {@code mountPoint} on {@code machineName} and report whether it
     * crossed a boundary since the last observation for that filesystem.
     */
    public DiskPressureTracker.Transition update(String machineName, String mountPoint,
                                                 boolean aboveThreshold) {
        return perFilesystem
            .computeIfAbsent(machineName + '\0' + mountPoint, k -> new DiskPressureTracker())
            .update(aboveThreshold);
    }
}
