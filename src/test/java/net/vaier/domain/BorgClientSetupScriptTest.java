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
    void generate_isIdempotent_earlyExitsWhenBorgAlreadyInstalled() {
        String s = BorgClientSetupScript.generate();

        assertThat(s).contains("if command -v borg >/dev/null 2>&1; then");
        assertThat(s).contains("borg already installed");
        assertThat(s).contains("exit 0");
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
