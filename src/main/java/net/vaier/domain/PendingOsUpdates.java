package net.vaier.domain;

import net.vaier.domain.port.ForHoldingPendingOsUpdates;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRunningSshCommands;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A machine's <b>pending OS updates</b>: how many package updates apt offers, and how many of them come from
 * a security suite. Read on the five-minute sweep, riding in front of its {@code df} on the same sign-in.
 *
 * <p>The read never refreshes the package lists (that is a write, and needs root): it reports what the
 * machine itself last fetched. A machine without apt, or one whose apt did not answer in time, has no
 * reading at all — unknown, never "none".
 *
 * @param total    every upgradable package
 * @param security those offered from a {@code -security} suite
 */
public record PendingOsUpdates(MachineId machineId, int total, int security) {

    public enum Verdict { NONE, ROUTINE, SECURITY }

    private static final String RC_MARKER = "VAIER-APT-RC=";
    private static final String PACKAGE_MARKER = "VAIER-UPGRADABLE=";
    private static final Pattern RC_LINE = Pattern.compile("^" + RC_MARKER + "(\\d+)$", Pattern.MULTILINE);
    private static final Pattern PACKAGE_LINE = Pattern.compile("^" + PACKAGE_MARKER + "(\\S+)$", Pattern.MULTILINE);

    /**
     * The read, printed as marker lines a {@code df} row can never be mistaken for. Silent on stderr, and it
     * leaves the exit status to whatever runs after it.
     */
    public static final String READ = "{ if command -v apt >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then "
        + "u=$(timeout 15 apt list --upgradable 2>/dev/null); echo " + RC_MARKER + "$?; "
        + "printf '%s\\n' \"$u\" | sed -n 's/^\\([^ ]*\\) .*\\[upgradable from.*$/" + PACKAGE_MARKER + "\\1/p'; "
        + "fi; } 2>/dev/null";

    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "os-updates-changed";

    /** {@code command} with the read run ahead of it, as one command for one connection. */
    public static String readAheadOf(String command) {
        return READ + "; " + command;
    }

    /** What apt said, or empty when it said nothing Vaier can call a verdict. */
    public static Optional<PendingOsUpdates> readFrom(MachineId machineId, CommandResult result) {
        if (result == null || result.stdout() == null) return Optional.empty();
        Matcher rc = RC_LINE.matcher(result.stdout());
        if (!rc.find() || !"0".equals(rc.group(1))) return Optional.empty();
        int total = 0;
        int security = 0;
        Matcher pkg = PACKAGE_LINE.matcher(result.stdout());
        while (pkg.find()) {
            total++;
            if (pkg.group(1).contains("-security")) security++;
        }
        return Optional.of(new PendingOsUpdates(machineId, total, security));
    }

    /**
     * Keep what this reading says, telling open browsers only when it changed. A reading not taken keeps the
     * last good one: not knowing is not the same as nothing waiting.
     */
    public static void retain(MachineId machineId, CommandResult result, ForHoldingPendingOsUpdates holder,
                              ForPublishingEvents events) {
        readFrom(machineId, result).ifPresent(reading -> {
            if (!holder.record(reading).filter(reading::equals).isPresent()) {
                events.publish(SSE_TOPIC, SSE_EVENT, "");
            }
        });
    }

    /** Ask {@code target} again on its own — after an install — and keep the answer. */
    public static void reread(MachineId machineId, SshTarget target, ForRunningSshCommands ssh,
                              ForHoldingPendingOsUpdates holder, ForPublishingEvents events) {
        retain(machineId, ssh.run(target, READ), holder, events);
    }

    public Verdict verdict() {
        if (security > 0) return Verdict.SECURITY;
        return total > 0 ? Verdict.ROUTINE : Verdict.NONE;
    }

    /** The one line a machine's page says; nothing at all when nothing waits. */
    public Optional<String> sentence() {
        if (security > 0) {
            String head = security + (security == 1 ? " security update waiting" : " security updates waiting");
            return Optional.of(total > security ? head + " · " + total + " in all" : head);
        }
        if (total == 0) return Optional.empty();
        return Optional.of(total + (total == 1 ? " update waiting" : " updates waiting") + " · none urgent");
    }

    /** The short mark a fleet card wears — only for security updates; routine ones never mark a card. */
    public Optional<String> chip() {
        if (security == 0) return Optional.empty();
        return Optional.of(security + (security == 1 ? " security update" : " security updates"));
    }
}
