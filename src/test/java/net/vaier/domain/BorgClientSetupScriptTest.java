package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BorgClientSetupScriptTest {

    @Test
    void generate_startsWithBashShebangAndStrictMode_asRoot() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).startsWith("#!/usr/bin/env bash");
        assertThat(s).contains("set -euo pipefail");
        // Installing packages needs root; mirror the server script's root check.
        assertThat(s).contains("if [ \"$(id -u)\" -ne 0 ]; then");
        assertThat(s).contains("exit 2");
    }

    @Test
    void generate_isIdempotent_skipsTheInstallWhenBorgIsAlreadyPresent() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("if command -v borg >/dev/null 2>&1; then");
        assertThat(s).contains("borg already installed");
    }

    /**
     * The script must NOT bail out the moment it finds borg. It used to {@code exit 0} there — which would
     * mean the sudoers drop-in below is never installed on any host that already has borg, i.e. on every host
     * in the fleet today. "Prepare client" would silently do nothing and "Back up as root" would never work.
     * Finding borg skips the INSTALL, and the script goes on to grant the sudo rule.
     */
    @Test
    void generate_stillInstallsTheSudoersRule_whenBorgIsAlreadyInstalled() {
        String s = BorgClientSetupScript.generate();

        int borgFound = s.indexOf("borg already installed");
        int sudoersInstall = s.indexOf("/etc/sudoers.d/vaier-borg");
        assertThat(borgFound).isGreaterThan(-1);
        assertThat(sudoersInstall).isGreaterThan(borgFound);
        // No early exit that would jump over the sudoers step.
        assertThat(s.substring(borgFound, sudoersInstall)).doesNotContain("exit 0");
    }

    // --- The sudoers drop-in that lets "Back up as root" work: borg as root, and NOTHING else ---

    /**
     * The grant: the SSH user may run the borg binary as root without a password, carrying the env vars borg
     * needs (SETENV — sudo would otherwise strip BORG_PASSCOMMAND/HOME/BORG_BASE_DIR and the run would hang
     * asking for a passphrase). Both borg paths are listed: apt installs to /usr/bin, pip/pipx to
     * /usr/local/bin.
     */
    @Test
    void generate_installsASudoersDropInPermittingBorgAsRoot() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("ALL=(root) NOPASSWD: SETENV: /usr/bin/borg, /usr/local/bin/borg");
        assertThat(s).contains("/etc/sudoers.d/vaier-borg");
        // The grantee is the invoking SSH user (the script itself runs as root via sudo).
        assertThat(s).contains("${SUDO_USER:-$(logname");
    }

    /**
     * SECURITY: the Cmnd list is borg's absolute paths and NOTHING else. A {@code sudo env}, a {@code sudo sh},
     * or an ALL/wildcard here would be a trivial root shell for anyone who can run it — turning a backup
     * feature into a local privilege-escalation hole.
     */
    @Test
    void generate_sudoersRuleGrantsOnlyBorg_neverAShellOrEnvOrWildcard() {
        String s = BorgClientSetupScript.generate();

        String rule = s.lines().filter(l -> l.contains("ALL=(root) NOPASSWD:")).findFirst().orElseThrow();
        assertThat(rule).doesNotContain("ALL=(root) NOPASSWD: ALL");
        assertThat(rule).doesNotContain("/bin/sh").doesNotContain("/bin/bash").doesNotContain("/usr/bin/env");
        assertThat(rule).doesNotContain("*");
        // Exactly the two borg binaries.
        String cmnds = rule.substring(rule.indexOf("SETENV:") + "SETENV:".length()).trim();
        // Trim the trailing printf format/quoting so only the command list remains.
        assertThat(cmnds).startsWith("/usr/bin/borg, /usr/local/bin/borg");
    }

    /**
     * A malformed sudoers drop-in can lock a host out of sudo ENTIRELY. So the file is validated with
     * {@code visudo -c} while it is still a temp file, and only a file that passes is installed — never
     * written straight into /etc/sudoers.d.
     */
    @Test
    void generate_validatesTheSudoersFileWithVisudoBeforeInstallingIt() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("visudo -cf");
        int validate = s.indexOf("visudo -cf");
        int install = s.indexOf("/etc/sudoers.d/vaier-borg");
        assertThat(validate).isLessThan(install);
    }

    /** sudoers files must be 0440 root:root, or sudo refuses to read them ("is mode 0644, should be 0440"). */
    @Test
    void generate_installsTheSudoersDropInAs0440RootOwned() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("install -o root -g root -m 0440");
    }

    /** Re-running must be safe: the drop-in is rewritten and re-validated, never appended to. */
    @Test
    void generate_sudoersDropInIsSafeToReRun() {
        String s = BorgClientSetupScript.generate();

        // Written to a temp file, then moved into place — never a >> append onto the live sudoers file.
        assertThat(s).doesNotContain(">> /etc/sudoers");
        assertThat(s).doesNotContain(">> \"$SUDOERS_TMP\"");
    }

    // --- The root-borg readiness probe: can this machine actually run borg as root? ---

    @Test
    void rootBorgProbe_echoesOkOnlyWhenSudoCanRunBorgWithoutAPassword() {
        String probe = BorgClientSetupScript.rootBorgProbe();

        assertThat(probe).contains("sudo -n borg --version");
        assertThat(probe).contains("ROOT_BORG_OK");
        assertThat(probe).contains("ROOT_BORG_ABSENT");
    }

    @Test
    void parseRootBorg_readsTheProbeResult() {
        assertThat(BorgClientSetupScript.parseRootBorg("ROOT_BORG_OK\n")).isTrue();
        assertThat(BorgClientSetupScript.parseRootBorg("ROOT_BORG_ABSENT\n")).isFalse();
        assertThat(BorgClientSetupScript.parseRootBorg(null)).isFalse();
        assertThat(BorgClientSetupScript.parseRootBorg("")).isFalse();
    }

    @Test
    void generate_widensPathBeforeProbingForThePackageManager() {
        // Some hosts keep binaries in /usr/local/bin or /sbin that a non-interactive sudo PATH omits.
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("export PATH=\"$PATH:/usr/local/bin:/usr/bin:/sbin:/usr/sbin\"");
    }

    @Test
    void generate_installsBorgbackupOnAptDnfYumApkZypper_andBorgOnPacman() {
        String s = BorgClientSetupScript.generate();

        // Debian/Ubuntu package name is borgbackup, and the index must be refreshed first.
        assertThat(s).contains("apt-get update && apt-get install -y borgbackup");
        // Fedora/RHEL.
        assertThat(s).contains("dnf install -y borgbackup");
        assertThat(s).contains("yum install -y borgbackup");
        // Alpine.
        assertThat(s).contains("apk add --no-cache borgbackup");
        // openSUSE.
        assertThat(s).contains("zypper install -y borgbackup");
        // Arch ships the package as 'borg', NOT 'borgbackup'.
        assertThat(s).contains("pacman -Sy --noconfirm borg");
        assertThat(s).doesNotContain("pacman -Sy --noconfirm borgbackup");
    }

    @Test
    void generate_detectsEachPackageManagerBeforeUsingIt() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("command -v apt-get");
        assertThat(s).contains("command -v dnf");
        assertThat(s).contains("command -v yum");
        assertThat(s).contains("command -v apk");
        assertThat(s).contains("command -v pacman");
        assertThat(s).contains("command -v zypper");
    }

    @Test
    void generate_failsLoudlyWhenNoSupportedPackageManagerIsPresent() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("no supported package manager");
        assertThat(s).contains("exit 5");
    }

    @Test
    void generate_verifiesBorgAtTheEnd() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("borg --version");
    }

    // --- The FUSE binding that makes `borg mount` work (Explorer slice D) ---

    /**
     * Debian's borg runs under the system python3, which ships NEITHER pyfuse3 NOR llfuse, so {@code borg
     * mount} fails on every fleet host with "no FUSE support". Installing python3-pyfuse3 fixes it. The
     * script installs the binding per package manager, exactly as it installs borg.
     */
    @Test
    void generate_installsAFuseBindingForBorgMount_onEachPackageManager() {
        String s = BorgClientSetupScript.generate();

        // Debian/Ubuntu, Fedora/RHEL, openSUSE ship it as python3-pyfuse3.
        assertThat(s).contains("apt-get install -y python3-pyfuse3");
        assertThat(s).contains("dnf install -y python3-pyfuse3");
        assertThat(s).contains("yum install -y python3-pyfuse3");
        assertThat(s).contains("zypper install -y python3-pyfuse3");
        // Alpine's py3- prefix, Arch's python- prefix.
        assertThat(s).contains("apk add --no-cache py3-pyfuse3");
        assertThat(s).contains("pacman -Sy --noconfirm python-pyfuse3");
    }

    /**
     * The trap this whole slice hinges on: every fleet host ALREADY has borg, so the borg-install branch is
     * skipped on all of them. If the FUSE install sat inside that branch it would never run on a single real
     * machine. It must sit OUTSIDE — after the borg-install if/else closes (past "Verifying borg").
     */
    @Test
    void generate_installsTheFuseBinding_outsideTheBorgAlreadyInstalledEarlyExit() {
        String s = BorgClientSetupScript.generate();

        int borgVerified = s.indexOf("==> Verifying borg");
        int fuseInstall = s.indexOf("pyfuse3");
        assertThat(borgVerified).isGreaterThan(-1);
        // The FUSE install is past the borg install branch, so it runs even when borg was already present.
        assertThat(fuseInstall).isGreaterThan(borgVerified);
    }

    /**
     * A host that cannot install the FUSE binding must keep BACKING UP — only its "browse the past" degrades.
     * So the FUSE install never aborts the script: it is reported, not fatal (no {@code exit} on its failure).
     */
    @Test
    void generate_aMissingFuseBindingIsReported_notFatal() {
        String s = BorgClientSetupScript.generate();

        int fuseStart = s.indexOf("FUSE support for borg mount");
        assertThat(fuseStart).isGreaterThan(-1);
        int sudoersGrant = s.indexOf("Granting borg-as-root");
        // Between announcing the FUSE install and the next step, nothing exits the script on failure.
        String fuseSection = s.substring(fuseStart, sudoersGrant > fuseStart ? sudoersGrant : s.length());
        assertThat(fuseSection).contains("WARNING");
        assertThat(fuseSection).doesNotContain("exit ");
    }

    @Test
    void generate_isValidBash() throws Exception {
        String s = BorgClientSetupScript.generate();

        File f = File.createTempFile("client-setup", ".sh");
        try {
            Files.writeString(f.toPath(), s);
            Process p = new ProcessBuilder("bash", "-n", f.getAbsolutePath())
                .redirectErrorStream(true).start();
            assertThat(p.waitFor()).isEqualTo(0);
        } finally {
            f.delete();
        }
    }

    // --- The passwordless-sudo probe: whether Vaier can install borg itself over SSH ---

    @Test
    void passwordlessSudoProbe_echoesSudoOkOnlyWhenSudoNeedsNoPassword() {
        String probe = BorgClientSetupScript.passwordlessSudoProbe();

        assertThat(probe).contains("sudo -n true");
        assertThat(probe).contains("SUDO_OK");
        assertThat(probe).contains("SUDO_ABSENT");
    }

    @Test
    void parsePasswordlessSudo_readsTheProbeResult() {
        assertThat(BorgClientSetupScript.parsePasswordlessSudo("SUDO_OK\n")).isTrue();
        assertThat(BorgClientSetupScript.parsePasswordlessSudo("SUDO_ABSENT\n")).isFalse();
        assertThat(BorgClientSetupScript.parsePasswordlessSudo(null)).isFalse();
    }

    // --- Detached launch: install under sudo, survive the 20 s SSH exec cap ---

    @Test
    void detachedLaunch_base64DecodesTheScriptThenNohupsItUnderSudoAndEchoesStarted() {
        String script = BorgClientSetupScript.generate();

        String launch = BorgClientSetupScript.detachedLaunch(script, "prepare-client-NUC-02",
            "/home/geir/.vaier-backup");

        assertThat(launch)
            .contains("W=/home/geir/.vaier-backup")
            .contains("mkdir -p \"$W\"")
            .contains("base64 -d > \"$W/prepare-client-NUC-02.sh\"")
            .contains("chmod +x \"$W/prepare-client-NUC-02.sh\"")
            .contains("nohup sh -c")
            .contains("& echo STARTED $!");
        // Unlike the server helper, the install needs root and Vaier SSHes as a non-root user, so the
        // script is launched under passwordless sudo.
        assertThat(launch).contains("sudo -n bash");
        assertThat(launch).contains("echo \\$? > \\\"$W/prepare-client-NUC-02.rc\\\"");
        assertThat(launch).contains("> \"$W/prepare-client-NUC-02.log\" 2>&1");
        // The raw install script never appears inline (it is base64-encoded).
        assertThat(launch).doesNotContain("apt-get install");
    }

    @Test
    void detachedLaunch_roundTripsToTheOriginalScriptWhichIsValidBash() throws Exception {
        String script = BorgClientSetupScript.generate();
        String launch = BorgClientSetupScript.detachedLaunch(script, "prepare-client-x",
            "/home/geir/.vaier-backup");

        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("printf %s '([A-Za-z0-9+/=]+)' \\| base64 -d").matcher(launch);
        assertThat(m.find()).isTrue();
        String decoded = new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(script);
    }
}
