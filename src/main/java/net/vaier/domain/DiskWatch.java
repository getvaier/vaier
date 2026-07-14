package net.vaier.domain;

/**
 * Whether Vaier watches one {@link RemoteDiskUsage filesystem} on one machine, and at what threshold —
 * the per-machine, per-filesystem knob that #325 added, because no single global rule is correct across a
 * fleet. {@code /} at 88% is normal on the NAS (it is the fixed-size DSM system partition) and would be an
 * emergency on Apalveien 5.
 *
 * <p>A watch is keyed on <b>machine and mount point together</b>. One mount point means two different
 * things on two different machines, so a watch keyed on the mount alone would mute the wrong disk.
 *
 * <p><b>The default is watched, at the global threshold</b> ({@link #watchedByDefault}) — see
 * {@link DiskWatches#forFilesystem}. That is deliberate and must not be inverted: the failure #325 fixes is
 * <em>silence about the disk that matters</em>, so a filesystem nobody has configured nags rather than
 * hides. Muting is a decision someone takes, never one they inherit.
 *
 * @param machineName      the machine the filesystem is on
 * @param mountPoint       the filesystem's mount point (e.g. {@code /volume1})
 * @param watched          whether Vaier alerts on this filesystem at all
 * @param thresholdPercent this filesystem's own alert threshold (1–100), or null to use the global one
 */
public record DiskWatch(String machineName, String mountPoint, boolean watched, Integer thresholdPercent) {

    public DiskWatch {
        if (machineName == null || machineName.isBlank()) {
            throw new IllegalArgumentException("A disk watch must name a machine");
        }
        if (mountPoint == null || mountPoint.isBlank()) {
            throw new IllegalArgumentException("A disk watch must name a mount point");
        }
        if (thresholdPercent != null && (thresholdPercent < 1 || thresholdPercent > 100)) {
            throw new IllegalArgumentException(
                "A disk watch threshold must be between 1 and 100, was " + thresholdPercent);
        }
    }

    /**
     * The watch a filesystem has when nobody has configured it: watched, at the global threshold. Nothing
     * is ever silently unwatched.
     */
    public static DiskWatch watchedByDefault(String machineName, String mountPoint) {
        return new DiskWatch(machineName, mountPoint, true, null);
    }

    /**
     * A watch from a stored record whose {@code watched} flag may be absent — a hand-edited or truncated
     * {@code disk-watches.yml}. <b>Absent means watched.</b>
     *
     * <p>The policy lives here rather than in the file adapter on purpose: "nothing is ever silently
     * unwatched" is the whole point of #325, and a store that could quietly hold a different default would be
     * a second place to invert it.
     */
    public static DiskWatch of(String machineName, String mountPoint, Boolean watched,
                               Integer thresholdPercent) {
        return new DiskWatch(machineName, mountPoint, watched == null || watched, thresholdPercent);
    }

    /**
     * Whether this watch is the one for {@code mountPoint} on {@code machineName} — machine <em>and</em>
     * mount together, because one mount point means two different disks on two different machines.
     *
     * <p>It lives on the entity because it is a watch's <b>identity</b>: the store replaces a watch by it, and
     * an identity asked in two places can drift in two directions.
     */
    public boolean isFor(String machineName, String mountPoint) {
        return this.machineName.equals(machineName) && this.mountPoint.equals(mountPoint);
    }

    /** This filesystem's own threshold when it has one, otherwise the global disk alert threshold. */
    public int effectiveThreshold(int globalThresholdPercent) {
        return thresholdPercent != null ? thresholdPercent : globalThresholdPercent;
    }
}
