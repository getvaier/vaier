package net.vaier.application;

import java.util.List;

/**
 * Read a machine's filesystems, on demand (#323 slice C, fixed by #325).
 *
 * <p>Vaier has computed this on a schedule since the disk alerts shipped, but only ever <em>emailed</em>
 * about it — and it read {@code df -P /}, so what it emailed was the root filesystem and only the root
 * filesystem. On the NAS that is the 2.3 GB DSM system partition, 88% by design and never moving, while
 * {@code /volume1} — 11.6 TB, holding every borg backup — was invisible to Vaier entirely. So this returns
 * <b>every real filesystem</b>, each carrying its <b>size</b>, rather than one number pretending to be
 * "the disk".
 *
 * <p>Every reading carries the threshold it was judged against and the domain's own verdict, so no caller
 * ever recombines "how full" with "how full is too full" — {@code RemoteDiskUsage.breaches} decides that,
 * once, for the alert email and the Explorer alike.
 *
 * <p>Throws {@code DiskUnreadableException} when the disk cannot be read — never an empty list, which would
 * read as "this machine has nothing to watch" and be the #325 silence all over again.
 */
public interface GetMachineDiskUsageUseCase {

    /** Every real filesystem on {@code machineName}, read now. */
    List<MachineFilesystemUco> getDiskUsage(String machineName);

    /**
     * One filesystem, as the Explorer and the API see it.
     *
     * @param machineName      the machine the filesystem is on
     * @param device           the backing device, as {@code df} names it
     * @param mountPoint       where it is mounted — {@code /volume1}, not just "the disk"
     * @param sizeKb           total capacity, in 1024-byte blocks
     * @param usedKb           capacity in use, in 1024-byte blocks
     * @param availableKb      capacity still free, in 1024-byte blocks
     * @param size             total capacity rendered for humans (e.g. {@code 10.8 TiB})
     * @param available        free capacity rendered for humans (e.g. {@code 6.6 TiB})
     * @param usedPercent      how full it is (0–100)
     * @param thresholdPercent the threshold it was actually judged against — its own, or the global one
     * @param watched          whether Vaier alerts on this filesystem at all
     * @param aboveThreshold   the domain's verdict: is this filesystem breaching its watch?
     */
    record MachineFilesystemUco(String machineName, String device, String mountPoint,
                                long sizeKb, long usedKb, long availableKb,
                                String size, String available,
                                int usedPercent, int thresholdPercent, boolean watched,
                                boolean aboveThreshold) {}
}
