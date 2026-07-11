package net.vaier.domain;

import org.junit.jupiter.api.Test;

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
}
