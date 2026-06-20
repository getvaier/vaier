package net.vaier.domain;

/**
 * A point-in-time reading of free space on a monitored filesystem. Owns the business decision
 * of how full the disk is ({@link #usedPercent()}) and whether that crosses an alert threshold
 * ({@link #isAbove(int)}), plus its own rendering into the admin disk-pressure email — the
 * notification service only sequences the SMTP send.
 *
 * @param path       the monitored filesystem path (e.g. the host root mounted at {@code /host})
 * @param totalBytes total capacity in bytes
 * @param usableBytes usable (free) space in bytes
 */
public record DiskUsage(String path, long totalBytes, long usableBytes) {

    /** Bytes currently in use — total minus usable. */
    public long usedBytes() {
        return totalBytes - usableBytes;
    }

    /** Percentage of capacity in use, rounded to the nearest whole percent. Zero when capacity is zero. */
    public int usedPercent() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) Math.round((usedBytes() * 100.0) / totalBytes);
    }

    /** Whether the disk is more than {@code thresholdPercent} full. Equal-to is not above. */
    public boolean isAbove(int thresholdPercent) {
        return usedPercent() > thresholdPercent;
    }

    /** Subject line for the disk-pressure alert email. */
    public String pressureSubject() {
        return "[Vaier] Host disk is " + usedPercent() + "% full";
    }

    /** Subject line for the recovery email, sent once the disk drops back below the threshold. */
    public String recoverySubject() {
        return "[Vaier] Host disk is back to " + usedPercent() + "% full";
    }

    /**
     * Body for the disk-pressure / recovery email. {@code thresholdPercent} is the configured
     * alert threshold; {@code baseDomain} builds the Vaier UI link, and when it is null or blank
     * the link is omitted.
     */
    public String pressureBody(int thresholdPercent, String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Monitored path: ").append(path).append("\n");
        body.append("Used: ").append(usedPercent()).append("%\n");
        body.append("Free: ").append(humanReadable(usableBytes)).append("\n");
        body.append("Total: ").append(humanReadable(totalBytes)).append("\n");
        body.append("Alert threshold: ").append(thresholdPercent).append("%\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }

    private static String humanReadable(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format(java.util.Locale.US, "%.1f GB", gb);
    }
}
