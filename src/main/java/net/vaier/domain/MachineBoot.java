package net.vaier.domain;

import net.vaier.domain.port.ForPersistingContainerStandings;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a machine last booted (#356), read off {@code /proc/uptime} on the five-minute sweep that is
 * already there.
 *
 * <p>It exists for one sentence. A container that was running and is not any more is the fact; <b>the
 * machine rebooted at 09:40</b> is what makes it legible, because a reboot explains everything else that
 * changed at that moment — and an unexplained one is worth knowing about in its own right. Without it the
 * operator reads "webtrees is not running" and has to go and work out why.
 *
 * <p>{@code /proc/uptime}'s first field rather than {@code uptime -s}, whose output format varies between
 * distributions and needs a timezone to be read at all. And it rides <b>in front of the sweep's own
 * command</b>, exactly as {@link DockerCommandAccess#probeAheadOf} does, so the fleet still costs one SSH
 * sign-in per machine per sweep: three questions, one connection.
 *
 * @param bootedAt when the machine came up
 */
public record MachineBoot(Instant bootedAt) {

    /** The marker the uptime is printed on — nothing else in the sweep's output looks like it. */
    private static final String MARKER = "VAIER-UPTIME=";

    private static final Pattern MARKER_LINE =
        Pattern.compile("^" + MARKER + "([0-9]+(?:\\.[0-9]+)?)$", Pattern.MULTILINE);

    /**
     * {@code command} with the uptime read run ahead of it, as one command for one connection.
     *
     * <p>Ahead of, and silent, for the reasons {@link DockerCommandAccess#probeAheadOf} is: {@code command}
     * still writes the last word on both streams and still supplies the exit status the caller judges it
     * by. The marker line cannot parse as a {@code df} row, so the disk reading it rides with is unchanged,
     * and a machine with no readable {@code /proc/uptime} simply prints an empty marker that reads as
     * nothing learned.
     */
    public static String readAheadOf(String command) {
        return "echo " + MARKER + "$(cut -d' ' -f1 /proc/uptime 2>/dev/null); " + command;
    }

    /**
     * When the machine booted, from what the sweep's output says. Empty when it carries no marker at all —
     * a trip that came back without one is a machine that was asleep or a command that never ran, and a
     * reading Vaier could not take is never recorded as one it did.
     */
    public static Optional<MachineBoot> readFrom(CommandResult result, Instant now) {
        if (result == null || result.stdout() == null) {
            return Optional.empty();
        }
        Matcher marker = MARKER_LINE.matcher(result.stdout().strip());
        if (!marker.find()) {
            return Optional.empty();
        }
        try {
            double secondsUp = Double.parseDouble(marker.group(1));
            return Optional.of(
                new MachineBoot(Instant.ofEpochSecond(now.getEpochSecond() - (long) secondsUp)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Remember when {@code machineId} booted, if this reading says. The domain owns the port call; the
     * sweep hands the port in and decides nothing — including the decision that a failed read leaves the
     * last good boot instant standing rather than erasing it.
     */
    public static void retain(MachineId machineId, CommandResult result,
                              ForPersistingContainerStandings standings, Instant now) {
        readFrom(result, now).ifPresent(boot -> standings.recordBoot(machineId, boot.bootedAt()));
    }
}
