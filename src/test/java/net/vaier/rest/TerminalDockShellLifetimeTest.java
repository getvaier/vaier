package net.vaier.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A persistent shell is a tmux session on the machine that deliberately outlives its WebSocket — that is what
 * lets a reconnect reattach to it. The flip side is that <em>nothing else ever ends it</em>, so the browser is
 * the only thing that can, and it has to get two things right or shells pile up on the fleet forever:
 *
 * <ol>
 *   <li>closing a pane must say so, so the session is killed rather than merely detached; and</li>
 *   <li>a pane id must outlive the page, or a reload forgets every id, mints new ones, and strands the old
 *       sessions with no name left to reach them by.</li>
 * </ol>
 *
 * <p>There is no JS test harness in this project, so — as with {@code TerminalDockScreenWakeTest} — the
 * invariants are pinned by asserting on the shipped asset itself.
 */
class TerminalDockShellLifetimeTest {

    private String dock() throws Exception {
        return Files.readString(Path.of("src/main/resources/static/terminal-dock.js"));
    }

    @Test
    void closingAPane_tellsTheServerToEndTheShell() throws Exception {
        // Without this frame the server cannot distinguish "I am done" from "my tunnel dropped", and a
        // persistent shell is built to survive the latter — so the session would linger forever.
        String dock = dock();
        assertThat(dock).contains("type: 'end-shell'");
        assertThat(dock).contains("function closeShell(id)");
    }

    @Test
    void theEndShellFrameGoesOutBeforeTheSocketIsClosed() throws Exception {
        // Ordering is the whole point: a frame queued after close() never reaches the server.
        String dock = dock();
        int endShell = dock.indexOf("type: 'end-shell'");
        int closeSocket = dock.indexOf("s.ws.onclose = null; s.ws.close();");
        assertThat(endShell).isGreaterThan(0);
        assertThat(closeSocket).isGreaterThan(endShell);
    }

    @Test
    void paneIdsSurviveAPageReload() throws Exception {
        // Held only in memory, a reload would forget every id and orphan its session on the host.
        assertThat(dock()).contains("localStorage.getItem(PANE_STORE_KEY)");
        assertThat(dock()).contains("localStorage.setItem(PANE_STORE_KEY");
    }

    @Test
    void openingAShell_reusesAnOwnedPaneIdBeforeMintingANewOne() throws Exception {
        // The reattach path: if we already own a session for this machine that is not on screen, open that one
        // rather than creating a second and leaving the first stranded.
        String dock = dock();
        assertThat(dock).contains("function claimPaneId(machineName)");
        assertThat(dock).contains("paneId: claimPaneId(machineName)");
        // The fresh-id fallback must not be reachable directly from open() any more.
        assertThat(dock).doesNotContain("paneId: randomPaneId()");
    }

    @Test
    void endingAShell_releasesItsPaneId() throws Exception {
        // An id we no longer own must not be handed to the next shell, or it would reattach to a dead session.
        String dock = dock();
        assertThat(dock).contains("function releasePaneId(machineName, paneId)");
        assertThat(dock).contains("releasePaneId(s.machine, s.paneId)");
    }
}
