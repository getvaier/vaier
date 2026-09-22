package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentShellTest {

    // --- session name: a safe, per-pane tmux identifier -------------------------------------------

    @Test
    void sessionName_prefixesVaier_andKeepsSafeCharacters() {
        assertThat(PersistentShell.sessionName("abc-123")).isEqualTo("vaier-abc-123");
        assertThat(PersistentShell.sessionName("A1_b-2")).isEqualTo("vaier-A1_b-2");
    }

    @Test
    void sessionName_stripsEverythingOutsideTheSafeCharset() {
        // A hostile pane id must never break out of the tmux command line — spaces, ;, /, $, quotes gone.
        assertThat(PersistentShell.sessionName("a b;rm -rf/")).isEqualTo("vaier-abrm-rf");
        assertThat(PersistentShell.sessionName("x$(whoami)`id`")).isEqualTo("vaier-xwhoamiid");
    }

    @Test
    void sessionName_blankOrAllUnsafe_fallsBackToDefault() {
        assertThat(PersistentShell.sessionName(";;;")).isEqualTo("vaier-default");
        assertThat(PersistentShell.sessionName("")).isEqualTo("vaier-default");
        assertThat(PersistentShell.sessionName(null)).isEqualTo("vaier-default");
    }

    // --- the shell command: attach-or-create tmux, fall back to a plain login shell ---------------

    @Test
    void attachOrCreateCommand_wrapsTmuxAttachOrCreate_forThisPane() {
        String cmd = PersistentShell.attachOrCreateCommand("pane1");
        // Attach-or-create with -A, detach stale clients with -D so the live client owns the window size.
        assertThat(cmd).contains("tmux new-session -A -D -s 'vaier-pane1'");
        assertThat(cmd).contains("exec ");            // exec so the shell process tree stays clean
    }

    @Test
    void attachOrCreateCommand_turnsOffTheTmuxStatusBar_forThisSessionOnly() {
        String cmd = PersistentShell.attachOrCreateCommand("pane1");
        // The web terminal supplies its own chrome; tmux's default (green) status bar only steals a row and
        // reads as clutter on a phone. Turn it off scoped to this Vaier session by name — the operator's own
        // tmux sessions keep theirs.
        assertThat(cmd).contains("set-option -t 'vaier-pane1' status off");
    }

    @Test
    void attachOrCreateCommand_fallsBackToPlainLoginShell_whenTmuxAbsent() {
        String cmd = PersistentShell.attachOrCreateCommand("pane1");
        assertThat(cmd).contains("command -v tmux");                 // detects tmux rather than assuming
        assertThat(cmd).contains("exec \"${SHELL:-/bin/sh}\" -l");   // graceful fallback: never fail to open
    }

    @Test
    void attachOrCreateCommand_quotesTheSessionName_evenForAHostilePane() {
        String cmd = PersistentShell.attachOrCreateCommand("a b;rm -rf/");
        assertThat(cmd).contains("-s 'vaier-abrm-rf'");
        assertThat(cmd).doesNotContain("rm -rf/");
    }

    // --- the probe: is tmux present, and does this pane's session already exist? ------------------

    @Test
    void probeCommand_probesTmuxPresenceAndSessionExistence() {
        String probe = PersistentShell.probeCommand("pane1");
        assertThat(probe).contains("command -v tmux");
        assertThat(probe).contains("has-session -t 'vaier-pane1'");
        assertThat(probe).contains("VAIER_TMUX_ABSENT");
        assertThat(probe).contains("VAIER_TMUX_ATTACH");
        assertThat(probe).contains("VAIER_TMUX_NEW");
    }

    // --- reading the probe into a truthful continuity ---------------------------------------------

    @Test
    void readProbe_existingSession_isReattached() {
        assertThat(PersistentShell.readProbe("VAIER_TMUX_ATTACH\n"))
            .isEqualTo(PersistentShell.Continuity.REATTACHED);
    }

    @Test
    void readProbe_noSessionYet_isNew() {
        assertThat(PersistentShell.readProbe("VAIER_TMUX_NEW\n"))
            .isEqualTo(PersistentShell.Continuity.NEW);
    }

    @Test
    void readProbe_tmuxAbsent_isPlain() {
        assertThat(PersistentShell.readProbe("VAIER_TMUX_ABSENT\n"))
            .isEqualTo(PersistentShell.Continuity.PLAIN);
    }

    @Test
    void readProbe_blankOrGarbled_neverClaimsContinuity_readsAsNew() {
        // Like the borg probes: never optimistically read reattachment we cannot prove.
        assertThat(PersistentShell.readProbe("")).isEqualTo(PersistentShell.Continuity.NEW);
        assertThat(PersistentShell.readProbe(null)).isEqualTo(PersistentShell.Continuity.NEW);
        assertThat(PersistentShell.readProbe("some banner noise")).isEqualTo(PersistentShell.Continuity.NEW);
    }

    // --- ending a shell: an explicit close kills the session, a dropped connection does not ---------

    @Test
    void endCommand_killsThisPanesSessionOnly() {
        String cmd = PersistentShell.endCommand("pane1");
        // Closing a pane is "I am done with this shell" — the tmux session must go, or it lingers detached
        // forever with whatever was running inside it. Scoped by name, so no other session is touched.
        assertThat(cmd).contains("tmux kill-session -t 'vaier-pane1'");
    }

    @Test
    void endCommand_survivesAnAlreadyDeadSession() {
        // The session may be gone already (host rebooted, operator killed it). Ending it is idempotent:
        // the command must still exit 0 so the close path never reports a spurious failure.
        assertThat(PersistentShell.endCommand("pane1")).contains("|| true");
    }

    @Test
    void endCommand_reducesAHostilePaneIdToTheSafeSessionName() {
        // Same guarantee as sessionName: a hostile pane id can never break out of the command line.
        assertThat(PersistentShell.endCommand("a b;rm -rf/")).contains("-t 'vaier-abrm-rf'");
        assertThat(PersistentShell.endCommand("a b;rm -rf/")).doesNotContain("rm -rf/");
    }

    // --- the shells already running on a machine (#322) -----------------------------------------------

    @Test
    void listCommand_listsTmuxSessions_withTheMachinesOwnClock_andSaysWhenTmuxIsAbsent() {
        String cmd = PersistentShell.listCommand();

        assertThat(cmd).contains("VAIER_TMUX_ABSENT");
        assertThat(cmd).contains("date +%s");
        assertThat(cmd).contains("tmux list-sessions -F");
        assertThat(cmd).contains("#{session_name}").contains("#{session_created}")
            .contains("#{session_last_attached}").contains("#{session_attached}").contains("#{pane_current_command}");
    }

    @Test
    void readShells_readsOnlyVaierSessions_withAgesFromTheMachinesOwnClock() {
        // The uuid is one Vaier mints: sessionName sanitises, so a name cannot be reversed to an arbitrary
        // pane id in general, but crypto.randomUUID output round-trips — pinned here, not discovered later.
        String uuid = "3826a934-1d23-4bc0-9f1e-0c2d4e6f8a10";
        String out = "VAIER_NOW 1000000\n"
            + "vaier-abc\t990000\t995000\t0\tclaude\n"       // detached; last held 5000 s ago
            + PersistentShell.sessionName(uuid) + "\t900000\t999000\t1\tbash\n"   // held by a window now
            + "vaier-p\t999700\t0\t0\tsh\n"                   // never attached: detached since created
            + "main\t100\t100\t1\tbash\nwork-vaier\t100\t100\t0\tvim\n";   // the operator's own

        List<RunningShell> shells = PersistentShell.readShells(out);

        assertThat(shells).extracting(RunningShell::paneId).containsExactly("abc", uuid, "p");
        RunningShell claude = shells.get(0);
        assertThat(claude.running()).isEqualTo("claude");
        assertThat(claude.age()).isEqualTo(Duration.ofSeconds(10000));
        assertThat(claude.sinceAttached()).isEqualTo(Duration.ofSeconds(5000));
        assertThat(claude.attached()).isFalse();
        assertThat(shells.get(1).attached()).isTrue();
        assertThat(shells.get(2).sinceAttached()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void readShells_absentTmux_noServer_garbageOrNothing_allReadAsNoShells() {
        assertThat(PersistentShell.readShells("VAIER_TMUX_ABSENT\n")).isEmpty();
        assertThat(PersistentShell.readShells("VAIER_NOW 5\n")).isEmpty();
        assertThat(PersistentShell.readShells("no server running on /tmp/tmux-1000/default\n")).isEmpty();
        assertThat(PersistentShell.readShells("VAIER_NOW 5\nvaier-broken\tnot-a-number\n")).isEmpty();
        assertThat(PersistentShell.readShells(null)).isEmpty();
    }

    @Test
    void readShells_roundTripsThePaneIdsVaierMints() {
        // sessionName sanitises, so a session name cannot be reversed to an arbitrary pane id — but the ids
        // Vaier mints (crypto.randomUUID) are already inside the charset and under the cap. Pin it.
        String paneId = "3826a934-1d23-4bc0-9f1e-0c2d4e6f8a10";
        String name = PersistentShell.sessionName(paneId);
        assertThat(name).isEqualTo("vaier-" + paneId);

        List<RunningShell> shells = PersistentShell.readShells("VAIER_NOW 10\n" + name + "\t1\t1\t0\tbash\n");
        assertThat(shells.get(0).paneId()).isEqualTo(paneId);
    }
}
