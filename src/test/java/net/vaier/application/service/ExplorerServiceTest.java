package net.vaier.application.service;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostCredential;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
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

    @InjectMocks ExplorerService service;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static SshTarget target(String pinnedFingerprint) {
        return SshTarget.on("10.13.13.6",
            new HostCredential("apalveien5", "root", AuthMethod.PASSWORD, "pw", null, false), pinnedFingerprint);
    }

    private void machineResolves(String machine, String pinnedFingerprint) {
        when(forResolvingSshTargets.resolve(machine)).thenReturn(target(pinnedFingerprint));
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

        List<FileEntry> entries = service.listDirectory("apalveien5", "/home/geir");

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
}
