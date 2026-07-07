package net.vaier.domain;

import java.util.Optional;

/**
 * A point-in-time reading of a machine's root-filesystem fullness, parsed from a {@code df -P /}
 * run over SSH — for any machine in the fleet, including the Vaier host reached via SSH-to-self. It owns
 * the business decisions of how to read {@code df} output ({@link #parse(String, String)}), whether the
 * disk crosses the alert threshold ({@link #isAbove(int)}), and its own rendering into the admin
 * remote-disk-pressure email — the notification service only sequences the SMTP send.
 *
 * <p>It reads the already-computed <b>Capacity</b> (Use%) column {@code df} reports, because that is
 * what the remote command returns; carrying the machine name lets the alert name the host that is under
 * pressure.
 *
 * @param machineName the machine the reading is for
 * @param usedPercent percentage of the root filesystem in use (0–100)
 */
public record RemoteDiskUsage(String machineName, int usedPercent) {

    /**
     * Read the used percentage from {@code df -P /} output. POSIX ({@code -P}) output is a header row
     * followed by one data row per filesystem, with a {@code Capacity} column rendered as {@code NN%}.
     * Returns empty when the output is blank, header-only, or has no percentage column (an unreachable
     * host, a {@code df} that failed, or unparseable output) — the caller treats empty as "cannot
     * tell", never as "disk full".
     */
    public static Optional<RemoteDiskUsage> parse(String machineName, String dfOutput) {
        if (dfOutput == null || dfOutput.isBlank()) {
            return Optional.empty();
        }
        String[] lines = dfOutput.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("Filesystem")) {
                continue;
            }
            for (String token : line.split("\\s+")) {
                if (token.matches("\\d{1,3}%")) {
                    int percent = Integer.parseInt(token.substring(0, token.length() - 1));
                    if (percent >= 0 && percent <= 100) {
                        return Optional.of(new RemoteDiskUsage(machineName, percent));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the disk is more than {@code thresholdPercent} full. Equal-to is not above. */
    public boolean isAbove(int thresholdPercent) {
        return usedPercent > thresholdPercent;
    }

    /** Subject line for the remote-disk-pressure alert email. */
    public String pressureSubject() {
        return "[Vaier] Disk on " + machineName + " is " + usedPercent + "% full";
    }

    /** Subject line for the recovery email, sent once the remote disk drops back below the threshold. */
    public String recoverySubject() {
        return "[Vaier] Disk on " + machineName + " is back to " + usedPercent + "% full";
    }

    /**
     * Body for the remote-disk-pressure / recovery email. {@code thresholdPercent} is the configured
     * alert threshold; {@code baseDomain} builds the Vaier UI link, and when it is null or blank the
     * link is omitted.
     */
    public String pressureBody(int thresholdPercent, String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Monitored path: /\n");
        body.append("Used: ").append(usedPercent).append("%\n");
        body.append("Alert threshold: ").append(thresholdPercent).append("%\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
