package net.vaier.application;

/**
 * Read one machine's root-filesystem fullness, on demand (#323 slice C).
 *
 * <p>Vaier has computed this on a schedule since the disk alerts shipped, but only ever <em>emailed</em>
 * about it — the number Vaier already knew could not be looked at. This is the read side of that same
 * reading: the same {@code df} over the same SSH exec port, taken when someone asks rather than when a
 * threshold is crossed.
 *
 * <p>The reading carries the threshold it is judged against and the domain's own verdict, so no caller
 * ever recombines "how full" with "how full is too full" — {@code RemoteDiskUsage.isAbove} decides that,
 * once, for the alert email and the Explorer alike.
 *
 * <p>Throws {@code DiskUnreadableException} when the disk cannot be read, never a zero.
 */
public interface GetMachineDiskUsageUseCase {

    /** The current disk reading for {@code machineName}. */
    MachineDiskUsageUco getDiskUsage(String machineName);

    /**
     * @param machineName     the machine the reading is for
     * @param usedPercent     percentage of the root filesystem in use (0–100)
     * @param thresholdPercent the configured disk-alert threshold this reading is judged against
     * @param aboveThreshold  the domain's verdict: is this disk under pressure?
     */
    record MachineDiskUsageUco(String machineName, int usedPercent, int thresholdPercent,
                               boolean aboveThreshold) {}
}
