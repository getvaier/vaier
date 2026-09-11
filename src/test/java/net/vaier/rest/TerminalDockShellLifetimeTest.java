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

    private String window() throws Exception {
        return read("terminal-window.js");
    }

    private String panes() throws Exception {
        return read("terminal-panes.js");
    }

    private String read(String asset) throws Exception {
        return Files.readString(Path.of("src/main/resources/static/" + asset));
    }

    @Test
    void endingAShell_tellsTheServerToEndIt() throws Exception {
        // Without this frame the server cannot distinguish "I am done" from "my tunnel dropped", and a
        // persistent shell is built to survive the latter — so the session would linger forever.
        assertThat(window()).contains("type: 'end-shell'");
    }

    @Test
    void theEndShellFrameGoesOutBeforeTheSocketIsClosed() throws Exception {
        // Ordering is the whole point: a frame queued after close() never reaches the server.
        String js = window();
        int endShell = js.indexOf("send({ type: 'end-shell' })");
        int closeSocket = js.indexOf("state.ws.onclose = null; state.ws.close();");
        assertThat(endShell).isGreaterThan(0);
        assertThat(closeSocket).isGreaterThan(endShell);
    }

    @Test
    void paneIdsSurviveAPageReload() throws Exception {
        // Held only in memory, a reload would forget every id and orphan its session on the host. Persistence
        // now lives in the shared terminal-panes.js — so the dock and the pop-out windows own sessions together,
        // and a shell handed from one to the other is never claimed by both — keyed the same as before so shells
        // opened before the split still reattach.
        String panes = panes();
        assertThat(panes).contains("localStorage.getItem");
        assertThat(panes).contains("localStorage.setItem");
        assertThat(panes).contains("'vaier.terminal.panes'");
        // The shell window reaches that persistence only through the shared module.
        assertThat(window()).contains("VaierPanes.claim(");
        assertThat(window()).contains("VaierPanes.release(");
    }

    // --- sessions are owned per machine IDENTITY (§6.22) -------------------------------------------

    @Test
    void shellSessionsAreOwnedByMachineIdentity_notByDisplayName() throws Exception {
        // The last thing in Vaier keyed by a machine's name. A shell is a tmux session that outlives its
        // socket, and the browser is the only thing that can ever end it — so filing the ids under a name
        // meant a rename lost every shell you had open on that machine: new ids minted under the new name,
        // the old sessions left running on the host with nothing left to reach them by. Two machines
        // sharing a name would likewise have handed one machine's session to the other.
        String panes = panes();
        assertThat(panes).contains("function claim(machineId, machineName)");
        assertThat(panes).contains("function primary(machineId, machineName)");
        assertThat(panes).contains("function adopt(machineId, paneId, machineName)");
        assertThat(panes).contains("function release(machineId, paneId, machineName)");
    }

    @Test
    void aPreIdentitySessionIsMovedAcross_ratherThanStranded() throws Exception {
        // Re-keying alone would have been the bug, not the fix: every session running at deploy time is
        // filed under its machine's NAME, so the first lookup by identity would find nothing, mint a fresh
        // id, and leave a live tmux session on the host that nothing can ever reach or kill again. The
        // entry is moved once, and only when the identity has nothing filed yet.
        String panes = panes();
        assertThat(panes).contains("function migrateLegacyName(machineId, machineName)");
        assertThat(panes).as("moved for both stores").contains("delete owned[machineName]")
            .contains("delete prim[machineName]");
        assertThat(panes).as("never clobbers what the identity already holds").contains("if (!prim[machineId])");
    }

    @Test
    void everyCallerHandsInBothTheIdentityAndTheName() throws Exception {
        // The name is passed for exactly one purpose — the one-time move above — so a caller that forgets
        // it silently strands that machine's running shells. Pinned here because the failure is invisible:
        // the new shell opens fine, and only the orphan on the host says anything went wrong.
        assertThat(read("terminal-window.js")).contains("VaierPanes.adopt(machineId, paneId, machine)")
            .contains("VaierPanes.claim(machineId, machine)")
            .contains("VaierPanes.release(machineId, paneId, machine)");
        assertThat(read("explorer-shell.js")).contains("VaierPanes.primary(machineId, machineName)");
    }

    @Test
    void aWindowClaimsItsPaneOnlyOnceItKnowsWhichMachineItIsOn() throws Exception {
        // A window opened from a pre-identity bookmark starts with no id and resolves one from /machines.
        // Claiming before that resolves files the session under `null` — every such window would then share
        // one bucket, and the first rename or reload would hand one machine's shell to another.
        String js = read("terminal-window.js");
        assertThat(js).contains("function claimPane()");
        int claimFn = js.indexOf("function claimPane()");
        int firstCall = js.indexOf("claimPane();", claimFn);
        assertThat(firstCall).as("claimed after the identity is in hand, never at parse time").isGreaterThan(claimFn);
    }

    @Test
    void endingAShell_releasesItsPaneId() throws Exception {
        // An id we no longer own must not be handed to the next shell, or it would reattach to a dead session.
        assertThat(window()).contains("VaierPanes.release(machineId, paneId, machine)");
    }

    @Test
    void theDockIsGone_soThereIsOneShellSystem() throws Exception {
        // terminal-dock.js tiled shells inside admin.html and was reached from a Terminal button on the
        // Infrastructure page — a page the Explorer replaced (#323). Nothing had called it since; its own
        // empty state still told operators to open a shell from a button that no longer existed. Two shell
        // systems is two places for session ownership to drift, which is the bug terminal-panes.js exists
        // to prevent.
        assertThat(Path.of("src/main/resources/static/terminal-dock.js")).doesNotExist();
        assertThat(read("styles.css")).as("its styles went with it").doesNotContain("term-");
    }
}
