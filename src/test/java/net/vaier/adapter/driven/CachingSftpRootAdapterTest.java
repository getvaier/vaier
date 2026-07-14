package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostCredential;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshHome;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForRunningSshCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Learning where a machine's SFTP subsystem is rooted (#326) — the adapter that asks, and remembers.
 *
 * <p>It asks the same question down both channels — where is the SSH user's home? — and hands the two answers
 * to {@link SftpRoot}, which decides what they mean. The adapter itself decides nothing: it is the probe and
 * the cache, and nothing else.
 *
 * <p>The exec half goes through {@link ForRunningSshCommands}, the very port the backups' {@code $HOME} probe
 * already runs on. There is deliberately no third way to reach a host.
 */
@ExtendWith(MockitoExtension.class)
class CachingSftpRootAdapterTest {

    @Mock ForRunningSshCommands forRunningSshCommands;
    @Mock ForBrowsingRemoteFiles forBrowsingRemoteFiles;

    @InjectMocks CachingSftpRootAdapter adapter;

    private static final SshTarget NAS = SshTarget.on("10.13.13.9",
        new HostCredential("NAS", "geir", AuthMethod.PASSWORD, "pw", null, false), "SHA256:pinned");

    /** What the exec channel answers the *physical* home probe with — the home with its symlinks resolved. */
    private void execHomeIs(String home) {
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, home, "", false, "SHA256:pinned"));
    }

    private void sftpHomeIs(String home) {
        when(forBrowsingRemoteFiles.home(any())).thenReturn(home);
    }

    // --- the NAS: an SFTP subsystem chrooted into /volume1 ----------------------------------------------

    @Test
    void rootFor_whenSftpSeesLessOfTheHomeThanTheExecChannel_isTheJailInFront() {
        execHomeIs("/volume1/homes/geir");   // what df, borg and the operator's own terminal call it
        sftpHomeIs("/homes/geir");           // what the Explorer, inside the jail, calls it

        SftpRoot root = adapter.rootFor("NAS", NAS);

        assertThat(root.jailed()).isTrue();
        assertThat(root.path()).isEqualTo("/volume1");
    }

    @Test
    void rootFor_asksTheExecChannel_withTheOneHomeProbeVaierAlreadyOwns() {
        execHomeIs("/volume1/homes/geir");
        sftpHomeIs("/homes/geir");

        adapter.rootFor("NAS", NAS);

        // The home, named physically: a jail is a physical subtree, so an aliased home can never line up with
        // one. Same channel and same port the backups' $HOME probe already runs on — no third way to reach a
        // host, just a more precise question.
        verify(forRunningSshCommands).run(NAS, SshHome.PHYSICAL_PROBE_COMMAND);
        verify(forBrowsingRemoteFiles).home(NAS);
    }

    // --- an ordinary machine: nothing to say ------------------------------------------------------------

    @Test
    void rootFor_whenBothChannelsAgree_isNoJailAtAll() {
        execHomeIs("/home/geir");
        sftpHomeIs("/home/geir");

        SftpRoot root = adapter.rootFor("Apalveien 5", NAS);

        assertThat(root.jailed()).isFalse();
        assertThat(root).isEqualTo(SftpRoot.NONE);
        assertThat(root.path()).isEqualTo("/");
    }

    // --- remembering ------------------------------------------------------------------------------------

    @Test
    void rootFor_isProbedOncePerMachine_thenRemembered() {
        execHomeIs("/volume1/homes/geir");
        sftpHomeIs("/homes/geir");

        assertThat(adapter.rootFor("NAS", NAS).path()).isEqualTo("/volume1");
        assertThat(adapter.rootFor("NAS", NAS).path()).isEqualTo("/volume1");
        assertThat(adapter.rootFor("NAS", NAS).path()).isEqualTo("/volume1");

        // A root does not move. Every directory the operator clicks would otherwise cost two extra SSH
        // connections to a machine on the far side of a VPN.
        verify(forRunningSshCommands, times(1)).run(any(), any());
        verify(forBrowsingRemoteFiles, times(1)).home(any());
    }

    @Test
    void rootFor_remembersPerMachine_notFleetWide() {
        execHomeIs("/home/geir");
        sftpHomeIs("/home/geir");
        assertThat(adapter.rootFor("Apalveien 5", NAS).jailed()).isFalse();

        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(0, "/volume1/homes/geir", "", false, "SHA256:pinned"));
        when(forBrowsingRemoteFiles.home(any())).thenReturn("/homes/geir");

        // One machine's jail must never be pinned onto another's paths.
        assertThat(adapter.rootFor("NAS", NAS).path()).isEqualTo("/volume1");
    }

    // --- what is not known is not guessed ---------------------------------------------------------------

    @Test
    void rootFor_aMachineThatCannotBeProbed_isNoJail_andIsNotRemembered() {
        // doThrow/doReturn rather than when(...): re-stubbing through when() would call the mock, and the mock
        // is currently stubbed to throw.
        doThrow(new SshConnectException("Connection refused", new RuntimeException()))
            .when(forRunningSshCommands).run(any(), any());

        // Unknown is safe; guessing is not. The machine's paths are left exactly as they are.
        assertThat(adapter.rootFor("NAS", NAS)).isEqualTo(SftpRoot.NONE);

        // And a blip must never poison the cache: a machine that was merely asleep gets asked again.
        doReturn(new CommandResult(0, "/volume1/homes/geir", "", false, "SHA256:pinned"))
            .when(forRunningSshCommands).run(any(), any());
        sftpHomeIs("/homes/geir");
        assertThat(adapter.rootFor("NAS", NAS).path()).isEqualTo("/volume1");
    }

    @Test
    void rootFor_anSftpChannelThatCannotBeAsked_isNoJail_andIsNotRemembered() {
        execHomeIs("/volume1/homes/geir");
        when(forBrowsingRemoteFiles.home(any()))
            .thenThrow(new SshConnectException("Connection refused", new RuntimeException()));

        assertThat(adapter.rootFor("NAS", NAS)).isEqualTo(SftpRoot.NONE);
    }

    @Test
    void rootFor_anExecProbeThatFails_isNoJail() {
        when(forRunningSshCommands.run(any(), any()))
            .thenReturn(new CommandResult(127, "", "sh: printf: not found", false, "SHA256:pinned"));

        assertThat(adapter.rootFor("NAS", NAS)).isEqualTo(SftpRoot.NONE);
        // The SFTP half is pointless once the exec half has no answer — and it costs a connection.
        verify(forBrowsingRemoteFiles, never()).home(any());
    }

    @Test
    void rootFor_twoHomesThatDoNotLineUp_isNoJail_ratherThanAnInventedMapping() {
        execHomeIs("/volume1/homes/geir");
        sftpHomeIs("/srv/elsewhere");   // not a suffix — Vaier does not understand this machine

        SftpRoot root = adapter.rootFor("NAS", NAS);

        assertThat(root).isEqualTo(SftpRoot.NONE);

        // This answer is stable, not transient — the machine answered, it just answered something Vaier cannot
        // read a jail out of. Asking again on every directory click would be two SSH connections per click,
        // forever, for an answer that will not change.
        adapter.rootFor("NAS", NAS);
        verify(forRunningSshCommands, times(1)).run(any(), any());
    }

    // --- the NAS as it really answers -------------------------------------------------------------------
    //
    // The shape the issue predicted is not the shape the machine has. DSM's SFTP subsystem canonicalises "."
    // to "/" — the jail root itself, which says nothing about where that root is — and $HOME comes back as
    // the symlink /var/services/homes/geir rather than the physical /volume1/homes/geir. Neither half of the
    // suffix trick arrives on its own, so the home has to be located inside the jail.

    @Test
    void rootFor_whenSftpOnlyAnswersWithTheJailRoot_locatesTheHomeInsideTheJail() {
        execHomeIs("/volume1/homes/geir");   // the physical home — `cd "$HOME" && pwd -P`, symlinks resolved
        sftpHomeIs("/");                     // DSM: "." is the jail root, and that tells us nothing

        // So the jailed half is asked which name it knows the home by, in the domain's order.
        when(forBrowsingRemoteFiles.firstDirectory(any(), eq(SftpRoot.jailCandidates("/volume1/homes/geir"))))
            .thenReturn(Optional.of("/homes/geir"));

        SftpRoot root = adapter.rootFor("NAS", NAS);

        assertThat(root.jailed()).isTrue();
        assertThat(root.path()).isEqualTo("/volume1");
    }

    @Test
    void rootFor_doesNotGoLooking_whenTheDirectAnswerAlreadyLinesUp() {
        execHomeIs("/home/geir");
        sftpHomeIs("/home/geir");

        assertThat(adapter.rootFor("Apalveien 5", NAS)).isEqualTo(SftpRoot.NONE);

        // An ordinary machine answers the direct question, and the search is never run at all — one exec
        // probe, one SFTP probe, exactly as before.
        verify(forBrowsingRemoteFiles, never()).firstDirectory(any(), any());
    }

    @Test
    void rootFor_whenTheJailCanSeeNoneOfTheHomesNames_isNoJail_ratherThanAGuess() {
        execHomeIs("/volume1/homes/geir");
        sftpHomeIs("/");
        when(forBrowsingRemoteFiles.firstDirectory(any(), any())).thenReturn(Optional.empty());

        assertThat(adapter.rootFor("NAS", NAS)).isEqualTo(SftpRoot.NONE);
    }
}
