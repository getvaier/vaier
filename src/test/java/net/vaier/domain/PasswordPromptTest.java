package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPromptTest {

    // --- the four real prompt forms, with and without a trailing space ---

    @Test
    void matches_barePasswordColon_bothCases() {
        assertThat(PasswordPrompt.isAwaitingPassword("Password:")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("password:")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("Password: ")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("password: ")).isTrue();
    }

    @Test
    void matches_sudoPasswordPrompt() {
        assertThat(PasswordPrompt.isAwaitingPassword("[sudo] password for geir:")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("[sudo] password for geir: ")).isTrue();
    }

    @Test
    void matches_sshUserAtHostPrompt() {
        assertThat(PasswordPrompt.isAwaitingPassword("geir@nas's password:")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("geir@nas's password: ")).isTrue();
    }

    @Test
    void matches_whenPromptIsPrecededByEarlierOutput() {
        assertThat(PasswordPrompt.isAwaitingPassword(
            "Last login: Tue\r\ngeir@nas's password: ")).isTrue();
    }

    @Test
    void matches_withTrailingNewlineOrWhitespaceAfterPrompt() {
        assertThat(PasswordPrompt.isAwaitingPassword("Password:\r\n")).isTrue();
        assertThat(PasswordPrompt.isAwaitingPassword("Password:   \t")).isTrue();
    }

    // --- the things it must NOT treat as a prompt ---

    @Test
    void rejects_ordinaryShellPrompt() {
        assertThat(PasswordPrompt.isAwaitingPassword("geir@openhab4:~$ ")).isFalse();
    }

    @Test
    void rejects_lineMerelyMentioningPassword() {
        assertThat(PasswordPrompt.isAwaitingPassword("Your password has been changed.")).isFalse();
        assertThat(PasswordPrompt.isAwaitingPassword("cat /etc/password\r\n")).isFalse();
    }

    @Test
    void rejects_blankAndNull() {
        assertThat(PasswordPrompt.isAwaitingPassword(null)).isFalse();
        assertThat(PasswordPrompt.isAwaitingPassword("")).isFalse();
        assertThat(PasswordPrompt.isAwaitingPassword("   \r\n")).isFalse();
    }

    @Test
    void rejects_promptThatIsNotAtTheTail() {
        // A password prompt was answered and a shell prompt followed — no longer awaiting a password.
        assertThat(PasswordPrompt.isAwaitingPassword(
            "[sudo] password for geir:\r\ngeir@openhab4:~$ ")).isFalse();
        assertThat(PasswordPrompt.isAwaitingPassword(
            "geir@nas's password:\r\nLinux nas 6.1.0\r\ngeir@nas:~$ ")).isFalse();
    }
}
