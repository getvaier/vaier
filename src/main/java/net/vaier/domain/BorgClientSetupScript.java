package net.vaier.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Generates the single bootstrap shell script a host runs to install the borg <em>client</em> it needs to
 * push fleet backups — the client-side counterpart to {@link BorgServerSetupScript}. A backup job on a host
 * with no borg installed dies with {@code exit 127} / {@code borg: not found} (a real incident on NUC 02):
 * Vaier provisions the borg <em>server</em> but never prepared the client. This closes that gap.
 *
 * <p>The script is pure, IO-free and safe to re-run: it exits early when borg is already installed, then
 * detects whatever package manager the host provides and installs the borg package under it. Package names
 * differ by distro — Debian/Ubuntu/Fedora/RHEL/Alpine/openSUSE ship {@code borgbackup}, but Arch ships it as
 * {@code borg} — so the name is chosen per manager. Installing packages needs root, so the script mirrors
 * {@link BorgServerSetupScript}'s root check and exits loudly when run as a normal user.
 *
 * <p>Unlike the server script it takes no arguments: everything (the package manager, whether borg is
 * already present) is detected on the host at run time, so one script fits every client.
 */
public final class BorgClientSetupScript {

    private BorgClientSetupScript() {}

    /**
     * Render the idempotent borg-client install script. It early-exits when borg is already installed,
     * widens {@code PATH} (some hosts keep the package manager in {@code /usr/local/bin} or {@code /sbin} a
     * non-interactive sudo shell omits), detects the package manager and installs the correct borg package,
     * failing loudly ({@code exit 5}) when none of apt/dnf/yum/apk/pacman/zypper is present, and finally
     * verifies {@code borg --version}.
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/usr/bin/env bash\n");
        sb.append("#\n");
        sb.append("# Vaier Backup client setup — idempotent, safe to re-run. Installs the borg client this\n");
        sb.append("# host needs to push fleet backups, using whatever package manager the host provides.\n");
        sb.append("#\n");
        sb.append("set -euo pipefail\n\n");

        sb.append("if [ \"$(id -u)\" -ne 0 ]; then\n");
        sb.append("    echo \"ERROR: run this script as root (sudo bash $0)\" >&2\n");
        sb.append("    exit 2\n");
        sb.append("fi\n\n");

        // Widen PATH before probing for a package manager: a non-interactive `sudo bash` PATH omits
        // /usr/local/bin and /sbin, where some distros keep their package tools (and later borg itself).
        sb.append("export PATH=\"$PATH:/usr/local/bin:/usr/bin:/sbin:/usr/sbin\"\n\n");

        sb.append("if command -v borg >/dev/null 2>&1; then\n");
        sb.append("    echo \"borg already installed: $(borg --version)\"\n");
        sb.append("    exit 0\n");
        sb.append("fi\n\n");

        sb.append("echo \"==> Installing borg...\"\n");
        sb.append("if command -v apt-get >/dev/null 2>&1; then\n");
        sb.append("    apt-get update && apt-get install -y borgbackup\n");
        sb.append("elif command -v dnf >/dev/null 2>&1; then\n");
        sb.append("    dnf install -y borgbackup\n");
        sb.append("elif command -v yum >/dev/null 2>&1; then\n");
        sb.append("    yum install -y borgbackup\n");
        sb.append("elif command -v apk >/dev/null 2>&1; then\n");
        sb.append("    apk add --no-cache borgbackup\n");
        // Arch's package is 'borg', not 'borgbackup' — getting this wrong makes the install fail on Arch.
        sb.append("elif command -v pacman >/dev/null 2>&1; then\n");
        sb.append("    pacman -Sy --noconfirm borg\n");
        sb.append("elif command -v zypper >/dev/null 2>&1; then\n");
        sb.append("    zypper install -y borgbackup\n");
        sb.append("else\n");
        sb.append("    echo \"ERROR: no supported package manager ")
            .append("(apt/dnf/yum/apk/pacman/zypper) found\" >&2\n");
        sb.append("    exit 5\n");
        sb.append("fi\n\n");

        sb.append("echo \"==> Verifying borg...\"\n");
        sb.append("borg --version\n");
        sb.append("echo \"==> Vaier Backup client setup complete.\"\n");
        return sb.toString();
    }

    /**
     * A bounded probe of whether the SSH user has <em>passwordless</em> sudo: {@code sudo -n true} succeeds
     * only when sudo needs no password (the {@code -n} flag never prompts). Echoes {@code SUDO_OK} then, and
     * {@code SUDO_ABSENT} otherwise. The client install needs root and Vaier SSHes as a non-root user, so
     * this decides whether Vaier can run the install itself (detached under sudo) or must degrade to staging
     * the script for the operator to run with {@code sudo bash}.
     */
    public static String passwordlessSudoProbe() {
        return "sudo -n true >/dev/null 2>&1 && echo SUDO_OK || echo SUDO_ABSENT";
    }

    /** Read a {@link #passwordlessSudoProbe} result: {@code SUDO_OK} means Vaier can install borg itself. */
    public static boolean parsePasswordlessSudo(String stdout) {
        return stdout != null && stdout.strip().contains("SUDO_OK");
    }

    /**
     * Wrap the rendered install {@code script} in a detached launcher that survives the 20 s SSH exec cap
     * (an {@code apt-get}/{@code dnf} install can easily exceed it). It mirrors
     * {@link BorgServerSetupScript#detachedLaunch} — base64-encode the script (so no character in it can
     * break the command line), decode it to {@code <workDir>/<runId>.sh}, make it executable and run it under
     * {@code nohup} with the exit code written to {@code <runId>.rc} and output to {@code <runId>.log} —
     * with one client-specific difference: the install needs root and Vaier SSHes non-root, so the script is
     * invoked under {@code sudo -n bash} rather than run directly. Only {@code STARTED <pid>} is echoed, so
     * the exec returns at once while the install continues; status is polled with the generic
     * {@link BorgCommand#pollStatus}/{@link BorgCommand#parsePoll}/{@link BorgCommand#fetchLog} over the same
     * rc/log files.
     */
    public static String detachedLaunch(String script, String runId, String workDir) {
        String b64 = base64(script);
        String scriptPath = "$W/" + runId + ".sh";
        return "W=" + workDir + "; mkdir -p \"$W\"; "
            + "printf %s '" + b64 + "' | base64 -d > \"" + scriptPath + "\"; "
            + "chmod +x \"" + scriptPath + "\"; "
            + "nohup sh -c \"sudo -n bash \\\"" + scriptPath + "\\\"; echo \\$? > \\\"$W/" + runId + ".rc\\\"\" "
            + "> \"$W/" + runId + ".log\" 2>&1 & echo STARTED $!";
    }

    /** Base64-encode a script so no character in it can break the shell command line it is embedded in. */
    private static String base64(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }
}
