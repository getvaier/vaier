package net.vaier.domain;

import java.time.Duration;

/**
 * A projection of when a machine's root filesystem will fill, derived from the recent fill trend of the
 * same {@code df} readings the disk-pressure watcher already takes. Where {@link RemoteDiskUsage} answers
 * "is the disk already too full?" (a <b>level</b>), this answers "how long until it is?" (a <b>trend</b>):
 * it carries the current fullness, the fill rate in percent-per-hour, and the <b>runway</b> — the
 * projected time until the filesystem reaches 100%.
 *
 * <p>Like {@link RemoteDiskUsage} it owns its own decisions and email rendering; the notification service
 * only sequences the SMTP send. The gate {@link #warrantsEarlyWarning(int)} deliberately goes quiet once
 * the disk reaches the level threshold, so a filling disk pages once as a forecast and then the existing
 * disk-pressure alert takes over — the operator is never paged twice for one disk.
 *
 * @param machineName             the machine the forecast is for
 * @param currentPercent          the most recent used percentage (0–100)
 * @param fillRatePercentPerHour  least-squares slope of recent samples, in percent-of-capacity per hour
 * @param runway                  projected time until the filesystem reaches 100%
 */
public record DiskFillForecast(String machineName, int currentPercent, double fillRatePercentPerHour,
                               Duration runway) {

    /**
     * The fixed early-warning horizon: when the {@link #runway} drops below 24h the forecast warrants an
     * early warning. Intentionally a constant, not a configurable setting — the disk-pressure level is the
     * tunable knob; the runway horizon is a fixed "you have less than a day" line.
     */
    public static final Duration FORECAST_HORIZON = Duration.ofHours(24);

    /**
     * Whether this forecast warrants an early warning: the runway is under the {@link #FORECAST_HORIZON}
     * AND the disk is still at or below the level threshold. The second clause is the hand-off gate — once
     * the disk crosses the level threshold the disk-pressure alert owns it, so the forecast falls silent.
     */
    public boolean warrantsEarlyWarning(int levelThreshold) {
        return runway.compareTo(FORECAST_HORIZON) < 0 && currentPercent <= levelThreshold;
    }

    /** Subject line for the early-warning email. */
    public String forecastSubject() {
        return "[Vaier] Disk on " + machineName + " projected full in ~" + approxRunwayHours() + "h";
    }

    /**
     * Body for the early-warning email. {@code baseDomain} builds the Vaier UI link, and when it is null
     * or blank the link is omitted.
     */
    public String forecastBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Monitored path: /\n");
        body.append("Used: ").append(currentPercent).append("%\n");
        body.append("Fill rate: ").append(String.format(java.util.Locale.ROOT, "%.1f", fillRatePercentPerHour))
            .append("%/h\n");
        body.append("Projected runway: ~").append(approxRunwayHours()).append("h until full\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }

    /** Runway rounded to whole hours, for human-readable subjects and bodies. */
    private long approxRunwayHours() {
        return Math.round(runway.toMinutes() / 60.0);
    }
}
