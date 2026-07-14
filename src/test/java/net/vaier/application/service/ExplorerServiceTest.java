package net.vaier.application.service;

import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostCredential;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import net.vaier.domain.port.ForResolvingSftpRoots;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Explorer service: it orchestrates a directory listing — validate the path (a domain decision),
 * resolve the machine to an SSH target, read it over SFTP, pin the host key on first use, and hand back
 * the entries in listing order. It holds no rules of its own.
 */
@ExtendWith(MockitoExtension.class)
class ExplorerServiceTest {

    @Mock ForResolvingSshTargets forResolvingSshTargets;
    @Mock ForBrowsingRemoteFiles forBrowsingRemoteFiles;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock ForResolvingSftpRoots forResolvingSftpRoots;

    @InjectMocks ExplorerService service;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static SshTarget target(String pinnedFingerprint) {
        return SshTarget.on("10.13.13.6",
            new HostCredential("apalveien5", "root", AuthMethod.PASSWORD, "pw", null, false), pinnedFingerprint);
    }

    private void machineResolves(String machine, String pinnedFingerprint) {
        when(forResolvingSshTargets.resolve(machine)).thenReturn(target(pinnedFingerprint));
        // Most of the fleet is not jailed, and on those machines nothing about a path changes.
        lenient().when(forResolvingSftpRoots.rootFor(eq(machine), any())).thenReturn(SftpRoot.NONE);
    }

    /** The NAS's shape: DSM chroots the SFTP subsystem into /volume1 and leaves the exec channel alone. */
    private void machineIsJailedIn(String machine, String rootPath) {
        when(forResolvingSshTargets.resolve(machine)).thenReturn(target("SHA256:pinned"));
        when(forResolvingSftpRoots.rootFor(eq(machine), any())).thenReturn(new SftpRoot(rootPath));
    }

    private void remoteAnswers(DirectoryListing listing) {
        when(forBrowsingRemoteFiles.list(any(), any())).thenReturn(listing);
    }

    @Test
    void listDirectory_resolvesTheMachine_readsThePath_andOrdersTheEntries() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN),
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN)), "SHA256:pinned"));

        List<FileEntry> entries = service.listDirectory("apalveien5", "/home/geir").entries();

        // Listing order is the domain's rule — directories before files — not the order the remote replied in.
        assertThat(entries).extracting(FileEntry::name).containsExactly("docs", "notes.txt");
        ArgumentCaptor<SshTarget> resolved = ArgumentCaptor.forClass(SshTarget.class);
        verify(forBrowsingRemoteFiles).list(resolved.capture(), eq("/home/geir"));
        assertThat(resolved.getValue().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void listDirectory_normalisesThePathBeforeItReachesTheMachine() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        service.listDirectory("apalveien5", "/home//geir/./docs/");

        verify(forBrowsingRemoteFiles).list(any(), eq("/home/geir/docs"));
    }

    @Test
    void listDirectory_aPathClimbingAboveTheRoot_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.listDirectory("apalveien5", "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The hostile path never reaches the machine — Vaier does not even open the connection.
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
        verify(forResolvingSshTargets, never()).resolve(any());
    }

    @Test
    void listDirectory_aRelativePath_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.listDirectory("apalveien5", "etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    @Test
    void listDirectory_firstUseOfAnUnpinnedMachine_pinsThePresentedHostKey() {
        // A machine may be browsed before it has ever had a terminal opened on it — Explorer must pin it
        // by the same trust-on-first-use rule, or it would connect to an unverified host every time.
        machineResolves("apalveien5", null);
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:fresh"));

        service.listDirectory("apalveien5", "/");

        verify(forTrackingHostKeys).pin("apalveien5", "SHA256:fresh");
    }

    @Test
    void listDirectory_anAlreadyPinnedMachine_isNotRepinned() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        service.listDirectory("apalveien5", "/");

        verify(forTrackingHostKeys, never()).pin(any(), any());
    }

    @Test
    void listDirectory_unknownMachine_propagatesNotFound() {
        when(forResolvingSshTargets.resolve("ghost")).thenThrow(new NotFoundException("Machine not found: ghost"));

        assertThatThrownBy(() -> service.listDirectory("ghost", "/"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listDirectory_machineWithoutACredential_propagatesNoHostCredential() {
        when(forResolvingSshTargets.resolve("apalveien5")).thenThrow(new NoHostCredentialException("apalveien5"));

        assertThatThrownBy(() -> service.listDirectory("apalveien5", "/"))
            .isInstanceOf(NoHostCredentialException.class);
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    // --- #326: a machine whose SFTP subsystem is chrooted -----------------------------------------------
    //
    // The NAS calls geir's home /volume1/homes/geir over the exec channel and /homes/geir over SFTP. The
    // Explorer must speak the machine's own coordinates — the ones df, borg and the operator's terminal use —
    // and translate down into the jail only to make the SFTP call itself.

    @Test
    void listDirectory_onAJailedMachine_asksSftpForTheJailPath_ofTheTruePathTheBrowserSent() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        service.listDirectory("NAS", "/volume1/homes");

        // The browser's path is a TRUE coordinate. SFTP, inside the jail, has never heard of /volume1.
        verify(forBrowsingRemoteFiles).list(any(), eq("/homes"));
    }

    @Test
    void listDirectory_onAJailedMachine_anchorsTheEntriesBackOntoTheirTruePaths() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/homes", "geir", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("NAS", "/volume1/homes");

        // One directory, one coordinate — the same one borg's source path and `df` use. Without this, #323's
        // coverage would compare /volume1/homes/geir with /homes/geir and report a backed-up directory as
        // uncovered.
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/volume1/homes/geir");
        assertThat(directory.path()).isEqualTo("/volume1/homes");
    }

    @Test
    void listDirectory_onAJailedMachine_carriesTheRoot_soTheBrowserKnowsWhereTheTreeBegins() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        assertThat(service.listDirectory("NAS", "/volume1").root().path()).isEqualTo("/volume1");
    }

    @Test
    void listDirectory_withNoPath_beginsAtTheMachinesOwnRoot_notAtSlash() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("NAS", null);

        // Anything above the root is unreachable over SFTP, so the tree begins there — and asking for "/" on
        // this machine would be asking for a path it cannot answer at all.
        assertThat(directory.path()).isEqualTo("/volume1");
        verify(forBrowsingRemoteFiles).list(any(), eq("/"));
    }

    @Test
    void listDirectory_aPathOutsideTheJail_isRefusedWithTheReason_notAnsweredWithAnEmptyDirectory() {
        machineIsJailedIn("NAS", "/volume1");

        assertThatThrownBy(() -> service.listDirectory("NAS", "/volume2"))
            .isInstanceOf(PathOutsideSftpRootException.class)
            .hasMessageContaining("/volume2")
            .hasMessageContaining("/volume1");

        // "I cannot reach that" is never rendered as "there is nothing there" — and never as the jail's own
        // contents wearing another path's name.
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    // --- the rest of the fleet is untouched -------------------------------------------------------------

    @Test
    void listDirectory_onAnUnjailedMachine_isExactlyWhatItAlwaysWas() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/home", "geir", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("apalveien5", "/home");

        // The regression that matters most: on a machine with no jail, every path is the path it always was.
        assertThat(directory.root()).isEqualTo(SftpRoot.NONE);
        assertThat(directory.root().path()).isEqualTo("/");
        assertThat(directory.path()).isEqualTo("/home");
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/home/geir");
        verify(forBrowsingRemoteFiles).list(any(), eq("/home"));
    }

    @Test
    void listDirectory_onAnUnprobeableMachine_leavesItsPathsAlone() {
        when(forResolvingSshTargets.resolve("asleep")).thenReturn(target("SHA256:pinned"));
        when(forResolvingSftpRoots.rootFor(eq("asleep"), any())).thenReturn(SftpRoot.NONE);
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        // A machine Vaier could not probe resolves to NONE, and NONE changes nothing. Unknown is safe.
        assertThat(service.listDirectory("asleep", "/etc").path()).isEqualTo("/etc");
        verify(forBrowsingRemoteFiles).list(any(), eq("/etc"));
    }

    @Test
    void listDirectory_aHostilePath_isRefusedBeforeTheMachineIsEvenProbed() {
        assertThatThrownBy(() -> service.listDirectory("NAS", "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The trust boundary still stands in front of everything: no SSH connection of any kind, not even the
        // two probes that learn the root.
        verify(forResolvingSftpRoots, never()).rootFor(any(), any());
        verify(forResolvingSshTargets, never()).resolve(any());
    }
}
