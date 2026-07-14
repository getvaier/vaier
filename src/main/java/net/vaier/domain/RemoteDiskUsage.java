package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A point-in-time reading of <b>one filesystem</b> on a machine, parsed from a {@code df -P} run over SSH —
 * for any machine in the fleet, including the Vaier host reached via SSH-to-self. It owns the business
 * decisions of how to read {@code df} output ({@link #parseList(String, String)}), which rows are real
 * filesystems at all ({@link #isPseudoFilesystem(String, String)}), whether a filesystem breaches its watch
 * ({@link #breaches(DiskWatch, int)}), and its own rendering into the admin remote-disk-pressure email — the
 * notification service only sequences the SMTP send.
 *
 * <p><b>#325.</b> This used to be a single number: {@code df -P /}, the root filesystem and only the root
 * filesystem. On the NAS {@code /} is the fixed-size 2.3 GB DSM system partition — 88% by design, and it
 * never moves — while {@code /volume1}, the 11.6 TB volume that holds every borg backup, was invisible to
 * Vaier and could have filled to 100% without a word. So a reading is now a <em>list</em> of filesystems,
 * and each one carries its <b>size</b> as well as its percentage: "88%" told the operator nothing they could
 * act on, and they rightly stopped trusting it. "/volume1 is at 91% (10.8 TiB, 1.0 TiB free)" does.
 *
 * @param machineName the machine the reading is for
 * @param device      the backing device, as {@code df} names it (e.g. {@code /dev/mapper/cachedev_1})
 * @param mountPoint  where the filesystem is mounted (e.g. {@code /volume1})
 * @param sizeKb      total capacity, in 1024-byte blocks
 * @param usedKb      capacity in use, in 1024-byte blocks
 * @param availableKb capacity still free, in 1024-byte blocks
 * @param usedPercent the already-computed Capacity (Use%) column {@code df} reports (0–100)
 */
public record RemoteDiskUsage(String machineName, String device, String mountPoint,
                              long sizeKb, long usedKb, long availableKb, int usedPercent) {

    /**
     * {@code df -P} — the command a disk reading is taken with. POSIX ({@code -P}) output guarantees one
     * non-wrapping data row per filesystem with a stable {@code Capacity} (Use%) column even for long device
     * names. It is deliberately <b>unscoped</b>: scoping it to {@code /} is precisely the #325 bug, because
     * the filesystem that matters is very often not the root one.
     *
     * <p>It lives here, next to {@link #parseList}, because how a disk reading is <em>taken</em> and how it
     * is <em>read</em> are one decision: change the command and the parser must change with it. The
     * scheduled watcher and the Explorer's on-demand read share this constant, so the alert email and the
     * tree can never end up measuring two different things.
     */
    public static final String DF_COMMAND = "df -P";

    /**
     * Devices that are never a real filesystem: kernel and in-memory mounts, plus {@code none}, which is
     * what Docker's aufs storage driver names its bind-mounts. On the NAS there are eight {@code none} rows,
     * every one an alias of {@code /volume1} — reporting them would show the same disk nine times and let a
     * single volume raise nine alerts.
     */
    private static final Set<String> PSEUDO_DEVICES = Set.of(
        "none", "tmpfs", "devtmpfs", "proc", "sysfs", "cgroup", "cgroup2", "squashfs", "overlay", "overlay2",
        "aufs", "devpts", "udev", "ramfs", "securityfs", "debugfs", "tracefs", "fusectl", "configfs",
        "pstore", "mqueue", "hugetlbfs", "binfmt_misc", "nsfs", "efivarfs");

    /**
     * Mount points below these are kernel or container plumbing, never an operator's disk. This catches the
     * mounts whose <em>device</em> is a real block device but whose mount point is machinery — Docker's
     * per-layer mounts most of all.
     */
    private static final List<String> PSEUDO_MOUNT_PREFIXES = List.of(
        "/proc", "/sys", "/dev", "/run", "/snap", "/var/lib/docker/", "/var/lib/containers/",
        "/var/lib/kubelet/");

    /** A mount point anywhere under a Docker storage driver's directory — an alias, not a disk. */
    private static final List<String> PSEUDO_MOUNT_FRAGMENTS = List.of(
        "/@docker/", "/docker/aufs/", "/docker/overlay2/", "/docker/btrfs/");

    /**
     * Whether a {@code df} row describes something that is not really a filesystem — a kernel/in-memory
     * mount, or a duplicate alias of a filesystem already reported.
     *
     * <p>This is a <b>domain decision</b>, not a parsing detail: it decides what Vaier is willing to call a
     * disk, and therefore what it will alert on and what it will show. It is the difference between the NAS
     * reporting three filesystems and reporting sixteen, eight of which are the same volume wearing a
     * Docker mask.
     */
    public static boolean isPseudoFilesystem(String device, String mountPoint) {
        if (device == null || mountPoint == null) {
            return true;
        }
        if (PSEUDO_DEVICES.contains(device.toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String fragment : PSEUDO_MOUNT_FRAGMENTS) {
            if (mountPoint.contains(fragment)) {
                return true;
            }
        }
        for (String prefix : PSEUDO_MOUNT_PREFIXES) {
            if (mountPoint.equals(prefix) || mountPoint.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read every real filesystem from {@code df -P} output. POSIX ({@code -P}) output is a header row
     * followed by one data row per filesystem: {@code Filesystem  1024-blocks  Used  Available  Capacity
     * Mounted on}. The mount point is the last column and may contain spaces, so it is taken as everything
     * after the {@code Capacity} column rather than by splitting on whitespace.
     *
     * <p><b>Total, like {@code Archive.parseList}:</b> it never throws. Blank output, a header-only run, a
     * {@code df} that failed, a shell that said {@code command not found} — all yield an empty list, and an
     * individual unparseable row is skipped while its siblings are kept. The caller treats an empty reading
     * as "cannot tell", never as "disk full".
     */
    public static List<RemoteDiskUsage> parseList(String machineName, String dfOutput) {
        List<RemoteDiskUsage> filesystems = new ArrayList<>();
        if (dfOutput == null || dfOutput.isBlank()) {
            return filesystems;
        }
        for (String rawLine : dfOutput.strip().split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("Filesystem")) {
                continue;
            }
            RemoteDiskUsage filesystem = parseRow(machineName, line);
            if (filesystem != null && !isPseudoFilesystem(filesystem.device(), filesystem.mountPoint())) {
                filesystems.add(filesystem);
            }
        }
        return filesystems;
    }

    /**
     * The shape of a {@code df -P} data row: device, 1024-blocks, used, available, capacity, mount point.
     * The mount point is the last column and <em>may contain spaces</em>, so it is matched as "the rest of
     * the line" rather than split on whitespace. A row that does not match this shape is not a row we
     * understand, and is skipped rather than guessed at.
     */
    private static final java.util.regex.Pattern DF_ROW = java.util.regex.Pattern.compile(
        "^(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d{1,3})%\\s+(.+)$");

    /** One {@code df -P} data row, or null when it will not parse. Never throws. */
    private static RemoteDiskUsage parseRow(String machineName, String line) {
        var row = DF_ROW.matcher(line);
        if (!row.matches()) {
            return null;
        }
        try {
            int usedPercent = Integer.parseInt(row.group(5));
            if (usedPercent > 100) {
                return null;
            }
            return new RemoteDiskUsage(machineName, row.group(1), row.group(6).strip(),
                Long.parseLong(row.group(2)), Long.parseLong(row.group(3)), Long.parseLong(row.group(4)),
                usedPercent);
        } catch (NumberFormatException e) {
            // A block count too large for a long — absurd, but a reading Vaier cannot trust is no reading.
            return null;
        }
    }

    /** Whether this filesystem is more than {@code thresholdPercent} full. Equal-to is not above. */
    public boolean isAbove(int thresholdPercent) {
        return usedPercent > thresholdPercent;
    }

    /**
     * The threshold this filesystem is judged against: its own when its {@link DiskWatch} carries one,
     * otherwise the global disk alert threshold.
     */
    public int effectiveThreshold(DiskWatch watch, int globalThresholdPercent) {
        return watch.effectiveThreshold(globalThresholdPercent);
    }

    /**
     * Whether this filesystem breaches its watch: it is watched at all, and it is above the threshold that
     * watch resolves to (its own, or the global one). The level half of {@link #judge}.
     */
    public boolean breaches(DiskWatch watch, int globalThresholdPercent) {
        return watch.watched() && isAbove(effectiveThreshold(watch, globalThresholdPercent));
    }

    /**
     * <b>The verdict, asked once.</b> Resolves mute, this filesystem's own threshold and the global fallback,
     * and hands back the whole answer: is Vaier watching, what is it judging against, and is this disk in
     * trouble.
     *
     * <p>Every consumer asks this <em>one</em> method — the scheduled watcher that sends the alert email, and
     * {@code MachineService.getDiskUsage} that feeds the Explorer's disk Inspector. That is deliberate and it
     * is the reason the constant, the parser and the verdict all live together on this entity: the email and
     * the tree must never be able to disagree about a disk, and the only way to guarantee that is for neither
     * of them to decide it.
     */
    public DiskVerdict judge(DiskWatch watch, int globalThresholdPercent) {
        return new DiskVerdict(watch.watched(), effectiveThreshold(watch, globalThresholdPercent),
            breaches(watch, globalThresholdPercent));
    }

    /**
     * What Vaier has decided about one filesystem: whether it is watched, the threshold it was actually judged
     * against (its own, or the global fallback), and whether it is breaching.
     *
     * @param watched         whether Vaier alerts on this filesystem at all
     * @param thresholdPercent the threshold it was judged against
     * @param breaching       whether it is over that threshold — always false when it is not watched
     */
    public record DiskVerdict(boolean watched, int thresholdPercent, boolean breaching) {

        /**
         * Whether Vaier says <b>nothing at all</b> about this filesystem — no level alert, and no fill
         * forecast either.
         *
         * <p>Stronger than "does not breach", and deliberately so. Muting means "do not speak about this
         * disk", and an early-warning email is speaking: a muted DSM system partition creeping from 88% to 89%
         * must not page the operator with a runway. That rule is a business decision, so it lives here rather
         * than as an {@code if} in the watcher — the only place it could otherwise have drifted from what the
         * Explorer shows.
         */
        public boolean silent() {
            return !watched;
        }
    }

    /** This filesystem's total capacity, for humans — e.g. {@code 10.8 TiB}. */
    public String sizeHuman() {
        return humanBlocks(sizeKb);
    }

    /** The capacity still free, for humans — e.g. {@code 6.6 TiB}. */
    public String availableHuman() {
        return humanBlocks(availableKb);
    }

    /**
     * 1024-byte blocks rendered the way {@code df -h} renders them — binary divisors, and IEC units that say
     * so. A number labelled "TB" that is really tebibytes is exactly the kind of quiet lie #325 is about, so
     * the unit tells the truth and the operator can reproduce it with {@code df -h} on the host.
     */
    private static String humanBlocks(long kb) {
        if (kb <= 0) {
            return "0 B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = kb;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /**
     * Subject line for the remote-disk-pressure alert email. It names the <b>mount</b> and its <b>size</b>,
     * not just the machine: "NAS is at 88%" is what sent the operator to DSM to find a disk that was nowhere
     * near full. "NAS /volume1 is at 91% (10.8 TiB, 1.0 TiB free)" is something they can act on.
     */
    public String pressureSubject() {
        return "[Vaier] " + machineName + " " + mountPoint + " is at " + usedPercent + "% full ("
            + sizeHuman() + ", " + availableHuman() + " free)";
    }

    /** Subject line for the recovery email, sent once the filesystem drops back below its threshold. */
    public String recoverySubject() {
        return "[Vaier] " + machineName + " " + mountPoint + " is back to " + usedPercent + "% full ("
            + availableHuman() + " free)";
    }

    /**
     * Body for the remote-disk-pressure / recovery email. {@code thresholdPercent} is the threshold this
     * filesystem was actually judged against — its own or the global one, already resolved by the domain —
     * and {@code baseDomain} builds the Vaier UI link, omitted when it is null or blank.
     */
    public String pressureBody(int thresholdPercent, String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Filesystem: ").append(mountPoint).append(" (").append(device).append(")\n");
        body.append("Size: ").append(sizeHuman()).append("\n");
        body.append("Used: ").append(usedPercent).append("% — ").append(availableHuman()).append(" free\n");
        body.append("Alert threshold: ").append(thresholdPercent).append("%\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
