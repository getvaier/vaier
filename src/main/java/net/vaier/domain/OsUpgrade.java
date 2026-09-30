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
 * <p>Root comes from logging in as root, from passwordless {@code sudo}, or — for a password login — from
 * {@code sudo} taking that login password on stdin, never on a command line. The borg grant that
 * {@link BorgClientSetupScript} installs covers borg alone, so it does not count.
 *
 * <p>A plain {@code upgrade} that may install new packages (a new kernel), never {@code dist-upgrade},
 * {@code full-upgrade} or {@code autoremove}: nothing is removed, and a config file the operator changed is
 * kept. Vaier never reboots; it says when one is due.
 */
public record OsUpgrade(MachineId machineId, String machineName, PackageManager packageManager, Root root) {

    public enum PackageManager { APT, DNF }

    /** How Vaier becomes root there. The password itself is never held here; it is the target's login secret. */
    public enum Root { LOGIN, PASSWORDLESS_SUDO, PASSWORD_SUDO }

    /** Minutes, not the exec default's seconds: a first upgrade in months downloads a lot over a home line. */
    public static final Duration UPGRADE_TIMEOUT = Duration.ofMinutes(30);

    /** Who Vaier is there, whether sudo asks for a password, which package managers exist, and if one is busy. */
    public static final String PROBE_COMMAND = "echo \"user=$(id -un) uid=$(id -u)\"; "
        + "sudo -n true 2>/dev/null && echo sudo=yes; "
        + "command -v apt-get >/dev/null 2>&1 && echo pm=apt; "
        + "command -v dnf >/dev/null 2>&1 && echo pm=dnf; "
        + "pgrep -x 'apt|apt-get|dpkg|dnf' >/dev/null 2>&1 && echo busy=yes; true";

    /** Whether sudo takes the login password, fed on stdin; {@code -p ''} keeps the prompt out of the output. */
    public static final String PASSWORD_SUDO_PROBE = "sudo -S -p '' true";
    public static final Duration PASSWORD_SUDO_PROBE_TIMEOUT = Duration.ofSeconds(20);

    private static final String APT_UPGRADE = "export DEBIAN_FRONTEND=noninteractive && apt-get update"
        + " && apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade --with-new-pkgs";
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
     * @throws ConflictException naming why, where Vaier cannot get root, the machine has neither apt nor dnf,
     *     or one is already running
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
        if (out.contains("busy=yes")) {
            throw new ConflictException(machineName + " is already installing updates. Try again when it is "
                + "done.");
        }
        return new OsUpgrade(machineId, machineName, packageManager, root(user, out, machineName, target, ssh));
    }

    private static Root root(Matcher user, String probed, String machineName, SshTarget target,
                             ForRunningSshCommands ssh) {
        if (user.group(2).equals("0")) {
            return Root.LOGIN;
        }
        if (probed.contains("sudo=yes")) {
            return Root.PASSWORDLESS_SUDO;
        }
        String login = user.group(1);
        boolean hasPassword = target.authMethod() == AuthMethod.PASSWORD
            && target.secret() != null && !target.secret().isEmpty();
        if (!hasPassword) {
            throw new ConflictException("Vaier logs in to " + machineName + " as " + login + ", who cannot use "
                + "sudo without a password, so it cannot install OS updates there. Log Vaier in as root, or give "
                + login + " passwordless sudo.");
        }
        CommandResult sudo = ssh.run(target, PASSWORD_SUDO_PROBE, PASSWORD_SUDO_PROBE_TIMEOUT, target.secret());
        if (sudo.timedOut() || sudo.exitCode() != 0) {
            throw new ConflictException("Vaier logs in to " + machineName + " as " + login + ", who cannot use "
                + "sudo with or without the login password, so it cannot install OS updates there. Log Vaier in "
                + "as root, or give " + login + " sudo.");
        }
        return Root.PASSWORD_SUDO;
    }

    public String upgradeCommand() {
        String line = packageManager == PackageManager.APT ? APT_UPGRADE : DNF_UPGRADE;
        return switch (root) {
            case LOGIN -> line;
            case PASSWORDLESS_SUDO -> "sudo -n sh -c '" + line + "'";
            case PASSWORD_SUDO -> "sudo -S -p '' sh -c '" + line + "'";
        };
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
            CommandResult upgrade = root == Root.PASSWORD_SUDO
                ? ssh.run(target, upgradeCommand(), UPGRADE_TIMEOUT, target.secret())
                : ssh.run(target, upgradeCommand(), UPGRADE_TIMEOUT);
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
