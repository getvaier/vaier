package net.vaier.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

// The terminal dock keeps a phone's screen awake while a shell is live, so a command you are watching but not
// touching does not vanish behind a dimmed display. There is no JS test harness in this project, so — as with
// docker-compose.yml (DockerComposeStructureTest) — the invariants are pinned by asserting on the shipped
// static asset itself. These guard the three things that make the feature correct rather than merely present.
class TerminalDockScreenWakeTest {

    private String dock() throws Exception {
        return Files.readString(Path.of("src/main/resources/static/terminal-dock.js"));
    }

    @Test
    void requestsAScreenWakeLock() throws Exception {
        assertThat(dock()).contains("navigator.wakeLock.request('screen')");
    }

    @Test
    void reacquiresTheLockWhenThePageBecomesVisibleAgain() throws Exception {
        // Browsers drop a wake lock whenever the tab is backgrounded; without re-acquiring on visibility the
        // screen would stay awake only until the first app-switch and never again.
        String dock = dock();
        assertThat(dock).contains("visibilitychange");
        assertThat(dock).contains("visibilityState");
    }

    @Test
    void holdsTheLockOnlyOnAPhoneAndOnlyWhileAShellIsOpen() throws Exception {
        // The lock is a phone concern (a desktop never sleeps mid-session) and must be released once the last
        // shell closes — otherwise opening a terminal once would pin the screen awake forever.
        String dock = dock();
        assertThat(dock).contains("if (!isPhone() || _terminals.size === 0) return;");
        assertThat(dock).contains("function releaseWakeLock()");
    }
}
