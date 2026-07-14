package net.vaier.domain;

/**
 * The all-clear for a machine whose {@link DiskFillForecast} early warning has ended in a genuine
 * recovery — the disk was drained, or its fill slowed enough that the projected runway rose back past the
 * {@link DiskFillForecast#FORECAST_HORIZON}, all while staying below the disk-pressure threshold.
 *
 * <p>Deliberately runway-free: a draining disk has no finite runway, so this carries only the machine and
 * its current fullness rather than fabricating a bogus projection. It is <em>not</em> emitted when the
 * forecast falls silent because the disk climbed past the disk-pressure threshold — that hand-off is
 * spoken for by the remote-disk-pressure alert, so raising an all-clear at the same poll would contradict
 * it.
 *
 * @param machineName    the machine whose fill forecast cleared
 * @param mountPoint     the filesystem whose fill forecast cleared (#325)
 * @param currentPercent the used percentage at the moment the forecast cleared (0–100)
 */
public record DiskFillForecastCleared(String machineName, String mountPoint, int currentPercent) {

    /** Subject line for the fill-forecast all-clear email. */
    public String clearedSubject() {
        return "[Vaier] " + machineName + " " + mountPoint + " fill forecast cleared";
    }

    /**
     * Body for the all-clear email. {@code baseDomain} builds the Vaier UI link, and when it is null or
     * blank the link is omitted.
     */
    public String clearedBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Filesystem: ").append(mountPoint).append("\n");
        body.append(mountPoint).append(" on ").append(machineName)
            .append(" is no longer trending toward full, now at ").append(currentPercent).append("%.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
