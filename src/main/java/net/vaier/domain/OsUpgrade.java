package net.vaier.domain;

import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForSendingAdminNotification;
import net.vaier.domain.port.ForTrackingHostKeys;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An <b>OS upgrade</b>: installing a machine's pending OS package updates with its own package manager, over
 * SSH, as root. Everything the act decides lives here — whether Vaier may do it at all, the command for apt
 * or dnf, how long it may take, and how the result reads.
 *
 * <p>Root comes from logging in as root, or from passwordless {@code sudo}; Vaier never types a password.
 * The borg grant that {@link BorgClientSetupScript} installs covers borg alone, so it does not count.
 *
 * <p>A plain {@code upgrade}, never {@code dist-upgrade}, {@code full-upgrade} or {@code autoremove}: nothing
 * is removed, and a config file the operator changed is kept. Vaier never reboots; it says when one is due.
 */
public record OsUpgrade(MachineId machineId, String machineName, PackageManager packageManager, boolean viaSudo) {

    public enum PackageManager { APT, DNF }

    /** Minutes, not the exec default's seconds: a first upgrade in months downloads a lot over a home line. */
    public static final Duration UPGRADE_TIMEOUT = Duration.ofMinutes(30);

    /** Who Vaier is there, whether sudo asks for a password, and which package managers exist. Never fails. */
    public static final String PROBE_COMMAND = "echo \"user=$(id -un) uid=$(id -u)\"; "
        + "sudo -n true 2>/dev/null && echo sudo=yes; "
        + "command -v apt-get >/dev/null 2>&1 && echo pm=apt; "
        + "command -v dnf >/dev/null 2>&1 && echo pm=dnf; true";

    private static final String APT_UPGRADE = "export DEBIAN_FRONTEND=noninteractive && apt-get update"
        + " && apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade";
    private static final String DNF_UPGRADE = "dnf -y upgrade";
    private static final String REBOOT_REQUIRED = "REBOOT_REQUIRED";

    private static final Pattern PROBE_USER = Pattern.compile("(?m)^user=(\\S*) uid=(\\d+)$");
    private static final Pattern APT_COUNT = Pattern.compile("(\\d+) upgraded, (\\d+) newly installed");
    private static final Pattern DNF_COUNT =
        Pattern.compile("(?m)^\\s*(?:Upgrade|Install|Upgrading|Installing):?\\s+(\\d+)\\s+[Pp]ackages?\\b");

    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "os-upgrade-settled";

    /**
     * Ask the machine how it would be upgraded, and judge the answer. Runs one short probe.
     *
     * @throws ConflictException naming why, where Vaier cannot get root or the machine has neither apt nor dnf
     */
    public static OsUpgrade of(MachineId machineId, String machineName, SshTarget target,
                               ForRunningSshCommands ssh, ForTrackingHostKeys hostKeys) {
        CommandResult probe = ssh.run(target, PROBE_COMMAND);
        target.pinOnFirstUse(probe.hostKeyFingerprint(), hostKeys);
        String out = probe.stdout() == null ? "" : probe.stdout();
        Matcher user = PROBE_USER.matcher(out);
        if (probe.timedOut() || !user.find()) {
            throw new ConflictException("Vaier could not ask " + machineName + " how it installs OS updates.");
        }
        PackageManager packageManager;
        if (out.contains("pm=apt")) {
            packageManager = PackageManager.APT;
        } else if (out.contains("pm=dnf")) {
            packageManager = PackageManager.DNF;
        } else {
            throw new ConflictException(machineName + " has neither apt nor dnf, the only package managers "
                + "Vaier installs OS updates with.");
        }
        boolean root = user.group(2).equals("0");
        if (!root && !out.contains("sudo=yes")) {
            String login = user.group(1);
            throw new ConflictException("Vaier logs in to " + machineName + " as " + login + ", who cannot use "
                + "sudo without a password, so it cannot install OS updates there. Log Vaier in as root, or give "
                + login + " passwordless sudo.");
        }
        return new OsUpgrade(machineId, machineName, packageManager, !root);
    }

    public String upgradeCommand() {
        String line = packageManager == PackageManager.APT ? APT_UPGRADE : DNF_UPGRADE;
        return viaSudo ? "sudo -n sh -c '" + line + "'" : line;
    }

    /** Prints {@code REBOOT_REQUIRED} when the machine wants one. Needs no root. */
    public String rebootRequiredCommand() {
        return packageManager == PackageManager.APT
            ? "test -f /var/run/reboot-required && echo " + REBOOT_REQUIRED + "; true"
            : "dnf needs-restarting -r 2>/dev/null | grep -q 'Reboot is required' && echo " + REBOOT_REQUIRED
                + "; true";
    }

    /** Carry it out and rule how it ended — always; a throwing port reads as unreachable. */
    public Settlement carryOut(SshTarget target, ForRunningSshCommands ssh) {
        try {
            CommandResult upgrade = ssh.run(target, upgradeCommand(), UPGRADE_TIMEOUT);
            if (upgrade.timedOut()) {
                return new Settlement(false, "Installing OS updates on " + machineName + " took over "
                    + UPGRADE_TIMEOUT.toMinutes() + " minutes, so Vaier stopped waiting. It may still be running "
                    + "there.", null);
            }
            if (upgrade.exitCode() != 0) {
                String said = lastLine(upgrade.stderr(), upgrade.stdout());
                return new Settlement(false, "Installing OS updates on " + machineName + " failed."
                    + (said == null ? "" : " The host said: " + said), said);
            }
            return new Settlement(true, upgradedSentence(changed(upgrade.stdout()), rebootRequired(target, ssh)), null);
        } catch (Exception e) {
            return new Settlement(false, "Vaier could not reach " + machineName + " to install its OS updates.",
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Where a settled upgrade is announced: the fleet stream the Explorer already holds open. */
    public void announce(Settlement settlement, ForPublishingEvents events) {
        events.publish(SSE_TOPIC, SSE_EVENT, "{"
            + "\"machineId\":\"" + JsonText.escaped(machineId.value()) + "\","
            + "\"upgraded\":" + settlement.upgraded() + ","
            + "\"message\":\"" + JsonText.escaped(settlement.sentence()) + "\"}");
    }

    /** Notify only on trouble: an upgrade that worked is said on the stream and nowhere else. */
    public void mailIfFailed(Settlement settlement, ForSendingAdminNotification mail) {
        if (!settlement.upgraded()) {
            mail.sendToAdmins("OS updates failed on " + machineName, settlement.sentence(), "OS upgrade");
        }
    }

    /**
     * How an OS upgrade ended: whether it did, the sentence the operator reads, and — when it did not — the
     * host's or the failure's own words for the log.
     */
    public record Settlement(boolean upgraded, String sentence, String diagnostic) {}

    /** A reboot check that cannot be read says nothing, rather than turning a done upgrade into a failure. */
    private boolean rebootRequired(SshTarget target, ForRunningSshCommands ssh) {
        try {
            return String.valueOf(ssh.run(target, rebootRequiredCommand()).stdout()).contains(REBOOT_REQUIRED);
        } catch (Exception e) {
            return false;
        }
    }

    /** Packages upgraded plus newly installed, or null when the output does not say. */
    private Integer changed(String stdout) {
        if (stdout == null) {
            return null;
        }
        if (packageManager == PackageManager.APT) {
            Matcher m = APT_COUNT.matcher(stdout);
            Integer count = null;
            while (m.find()) {
                count = Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2));
            }
            return count;
        }
        if (stdout.contains("Nothing to do")) {
            return 0;
        }
        Matcher m = DNF_COUNT.matcher(stdout);
        Integer count = null;
        while (m.find()) {
            count = (count == null ? 0 : count) + Integer.parseInt(m.group(1));
        }
        return count;
    }

    private String upgradedSentence(Integer changed, boolean reboot) {
        String done;
        if (changed == null) {
            done = machineName + " installed its OS updates.";
        } else if (changed == 0) {
            done = machineName + " had no OS updates to install.";
        } else {
            done = machineName + " installed " + changed + " package update" + (changed == 1 ? "" : "s") + ".";
        }
        return reboot ? done + " It needs a reboot to finish; Vaier did not reboot it." : done;
    }

    /** The last line that says anything, as one printable line, or null. */
    private static String lastLine(String... outputs) {
        for (String output : outputs) {
            if (output == null) {
                continue;
            }
            String[] lines = output.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String cleaned = lines[i].replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
                if (!cleaned.isEmpty()) {
                    return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 239) + "…";
                }
            }
        }
        return null;
    }
}
