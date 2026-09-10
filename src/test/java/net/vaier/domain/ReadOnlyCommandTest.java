package net.vaier.domain;

import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A <b>Read-only command</b> (#360): what the model may run on a machine through Chat, decided here and
 * nowhere else. "Chat can look, never change" is a promise the prompt makes; this is the mechanism that keeps
 * it, and it is a list of what is allowed, never a list of what is not.
 */
class ReadOnlyCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "apt list --upgradable", "apt-cache policy", "dpkg -l", "dnf check-update", "apk list -u",
        "pacman -Qu", "rpm -qa", "zypper lu",
        "uptime", "df -h", "free -m", "ps aux", "cat /etc/os-release", "ls -la /var/log",
        "tail -n 50 /var/log/syslog", "journalctl -u docker -n 100 --no-pager",
        "systemctl status docker", "systemctl list-timers", "docker ps -a", "docker logs --tail 50 mosquitto",
        "docker images", "docker system df", "docker compose ls", "wg show", "ip -br addr", "ss -tlnp",
        "/usr/bin/df -h", "find /var/log -name '*.log' -mtime -1", "grep -i error /var/log/syslog",
        "cat /etc/hostname | head -1", "ps aux | grep -i mosquitto | wc -l",
    })
    void aLookingCommandIsAllowed(String line) {
        assertThat(ReadOnlyCommand.of(line).line()).isEqualTo(line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /", "apt install vim", "apt-get update", "apt upgrade", "dnf update", "pacman -Syu",
        "docker rm -f mosquitto", "docker restart mosquitto", "docker compose up -d", "docker exec x sh",
        "systemctl restart docker", "reboot", "shutdown -h now", "sudo apt list --upgradable", "su -",
        "sh -c 'ls'", "bash", "xargs rm", "find / -name x -delete", "find / -exec rm {} +",
        "journalctl --vacuum-time=1d", "git pull", "chmod 777 /", "echo hi > /etc/motd", "tee /etc/x",
        "env", "printenv", "sed -i s/a/b/ /etc/x", "awk 'BEGIN{system(\"rm x\")}'", "docker inspect x",
        "docker compose config", "kill -9 1", "crontab -e", "nano /etc/x", "vi /etc/x",
        "ls | rm -rf /", "ls | sh", "hostname newname", "date -s 2020-01-01", "wg showconf wg0",
        "ip link set eth0 down", "dmesg -C",
    })
    void aCommandThatCouldChangeTheMachineIsRefused(String line) {
        assertThatThrownBy(() -> ReadOnlyCommand.of(line))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("can look, never change");
    }

    /** The refusal names what was refused, so the model can say so rather than try another spelling. */
    @Test
    void aRefusalNamesTheCommandItRefused() {
        assertThatThrownBy(() -> ReadOnlyCommand.of("apt install vim"))
            .hasMessageContaining("apt install");
        assertThatThrownBy(() -> ReadOnlyCommand.of("rm -rf /tmp/x"))
            .hasMessageContaining("rm");
    }

    /**
     * One command at a time. A pipe between looking commands is fine and is checked stage by stage; anything
     * that chains, redirects or opens a subshell is not, because the first word would be the only one read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "df -h; rm -rf /", "df -h && rm -rf /", "df -h || rm -rf /", "df -h & rm -rf /",
        "cat $(echo /etc/shadow)", "cat `echo /etc/shadow`", "df -h > /etc/x", "cat < /etc/x",
        "df -h\nrm -rf /", "ls |& rm x", "df -h 2>/dev/null",
    })
    void chainingRedirectingOrSubshellingIsRefused(String line) {
        assertThatThrownBy(() -> ReadOnlyCommand.of(line))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one command at a time");
    }

    /**
     * Defence in depth for the places Vaier itself puts a secret (the borg passphrase under .vaier-backup,
     * the login user's own keys) and the usual homes of one. `cat` is a looking command; `cat` of a key is
     * still a leak to the model and to Anthropic. Only path-shaped words are judged, so a container called
     * "wireguard" can still have its logs read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "cat ~/.ssh/id_ed25519", "cat /home/geir/.ssh/id_rsa", "cat /etc/shadow", "cat /etc/wireguard/wg0.conf",
        "cat ~/.vaier-backup/nas.pass", "ls /root/.vaier-backup", "cat ./.env", "cat /opt/app/.env",
        "cat /home/geir/.netrc", "cat /home/geir/.docker/config.json", "cat /proc/1/environ",
        "grep -r password /etc/app/credentials.yml", "cat ~/.aws/credentials", "cat /etc/x/server.key",
        "tail /var/lib/vaier/access.yml",
    })
    void whereSecretsLiveIsNeverRead(String line) {
        assertThatThrownBy(() -> ReadOnlyCommand.of(line))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("where secrets live");
    }

    @Test
    void aContainerNamedLikeASecretHomeIsStillJustAName() {
        assertThat(ReadOnlyCommand.of("docker logs wireguard --tail 20").line()).isNotNull();
        assertThat(ReadOnlyCommand.of("docker ps --filter name=wireguard").line()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void nothingToRunIsSaidAsSuch(String line) {
        assertThatThrownBy(() -> ReadOnlyCommand.of(line))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say what to run.");
    }

    @Test
    void nullIsNothingToRun() {
        assertThatThrownBy(() -> ReadOnlyCommand.of(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say what to run.");
    }

    @Test
    void anUnbalancedQuoteIsRefusedRatherThanGuessedAt() {
        assertThatThrownBy(() -> ReadOnlyCommand.of("grep 'error /var/log/syslog"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The domain runs it and pins the host on first use, exactly as the disk reading does. */
    @Test
    void runningItGoesThroughTheSshPort_andPinsTheHostOnFirstUse() {
        ForRunningSshCommands ssh = mock(ForRunningSshCommands.class);
        ForTrackingHostKeys hostKeys = mock(ForTrackingHostKeys.class);
        SshTarget target = mock(SshTarget.class);
        when(ssh.run(any(), anyString())).thenReturn(new CommandResult(0, "up 3 days", "", false, "SHA256:abc"));

        CommandOutcome outcome = ReadOnlyCommand.of("uptime").runOn(target, ssh, hostKeys);

        verify(ssh).run(target, "uptime");
        verify(target).pinOnFirstUse("SHA256:abc", hostKeys);
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.output()).isEqualTo("up 3 days");
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.cut()).isFalse();
    }

    @Test
    void theOutcomeCarriesStderrToo_becauseThatIsWhereACommandExplainsItself() {
        CommandOutcome outcome = CommandOutcome.of(new CommandResult(1, "", "apt: no such package", false, null));

        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.output()).isEqualTo("apt: no such package");
    }

    /** A chatty command is cut before it reaches the model; the model is told it was cut. */
    @Test
    void aLongOutputIsCutAndSaidToBe() {
        String chatty = "x".repeat(CommandOutcome.MAX_CHARS + 500);

        CommandOutcome outcome = CommandOutcome.of(new CommandResult(0, chatty, "", false, null));

        assertThat(outcome.output()).hasSize(CommandOutcome.MAX_CHARS);
        assertThat(outcome.cut()).isTrue();
    }

    @Test
    void whatIsAllowedIsSaidInOneSentenceTheModelCanRepeat() {
        assertThat(ReadOnlyCommand.WHAT_IS_ALLOWED).contains("apt").contains("journalctl").contains("docker");
    }
}
