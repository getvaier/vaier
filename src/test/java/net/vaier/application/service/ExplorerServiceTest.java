package net.vaier.application.service;

import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.ViewFileUseCase.View;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CannotDeleteSftpRootException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.FileEntry;
import net.vaier.domain.Upload;
import net.vaier.domain.HostCredential;
import net.vaier.domain.MachineId;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.Selection;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.Bundle;
import net.vaier.domain.port.ForHoldingBundles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import net.vaier.domain.Excludes;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.ViewableFile;
import net.vaier.domain.port.ForMountingArchives;
import net.vaier.domain.port.ForReadingProtectedPaths;
import net.vaier.domain.port.ForResolvingSftpRoots;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock ForResolvingSshTargets forResolvingSshTargets;
    @Mock ForBrowsingRemoteFiles forBrowsingRemoteFiles;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock ForResolvingSftpRoots forResolvingSftpRoots;
    @Mock ForMountingArchives forMountingArchives;
    @Mock ForReadingProtectedPaths forReadingProtectedPaths;
    @Mock ForHoldingBundles forHoldingBundles;

    @InjectMocks ExplorerService service;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static SshTarget target(String pinnedFingerprint) {
        return SshTarget.on("10.13.13.6",
            new HostCredential(mid("apalveien5"), "root", AuthMethod.PASSWORD, "pw", null, false), pinnedFingerprint);
    }

    private void machineResolves(String machine, String pinnedFingerprint) {
        when(forResolvingSshTargets.resolve(mid(machine))).thenReturn(target(pinnedFingerprint));
        // Most of the fleet is not jailed, and on those machines nothing about a path changes.
        lenient().when(forResolvingSftpRoots.rootFor(any())).thenReturn(SftpRoot.NONE);
    }

    /** The NAS's shape: DSM chroots the SFTP subsystem into /volume1 and leaves the exec channel alone. */
    private void machineIsJailedIn(String machine, String rootPath) {
        when(forResolvingSshTargets.resolve(mid(machine))).thenReturn(target("SHA256:pinned"));
        when(forResolvingSftpRoots.rootFor(any())).thenReturn(new SftpRoot(rootPath));
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

        List<FileEntry> entries = service.listDirectory(mid("apalveien5"), "/home/geir").entries();

        // Listing order is the domain's rule — directories before files — not the order the remote replied in.
        assertThat(entries).extracting(FileEntry::name).containsExactly("docs", "notes.txt");
        ArgumentCaptor<SshTarget> resolved = ArgumentCaptor.forClass(SshTarget.class);
        verify(forBrowsingRemoteFiles).list(resolved.capture(), eq("/home/geir"));
        assertThat(resolved.getValue().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void listDirectory_carriesTheMachinesProtectedPaths_soEntriesCanBeMarkedBackedUp() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forReadingProtectedPaths.protectedPathsFor(mid("apalveien5")))
            .thenReturn(ProtectedPaths.of(SourcePaths.of(List.of("/home/geir")), Excludes.none()));
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), "/home/geir");

        // The service asks the driven port (never a backup use case) and threads the domain value object back;
        // the coverage decision itself stays in the domain.
        assertThat(directory.protectedPaths().covers("/home/geir/docs")).isTrue();
        verify(forReadingProtectedPaths).protectedPathsFor(mid("apalveien5"));
    }

    @Test
    void listDirectory_inThePast_doesNotConsultProtectedPaths_andMarksNothing() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in(MOUNTPOINT + "/home/geir", "docs", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), "/home/geir", "ab12");

        // The past's backup shape is not today's — the protected set is empty and the port is never asked.
        assertThat(directory.protectedPaths().isEmpty()).isTrue();
        verify(forReadingProtectedPaths, never()).protectedPathsFor(any());
    }

    @Test
    void listDirectory_normalisesThePathBeforeItReachesTheMachine() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        service.listDirectory(mid("apalveien5"), "/home//geir/./docs/");

        verify(forBrowsingRemoteFiles).list(any(), eq("/home/geir/docs"));
    }

    @Test
    void listDirectory_aPathClimbingAboveTheRoot_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.listDirectory(mid("apalveien5"), "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The hostile path never reaches the machine — Vaier does not even open the connection.
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
        verify(forResolvingSshTargets, never()).resolve(any());
    }

    @Test
    void listDirectory_aRelativePath_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.listDirectory(mid("apalveien5"), "etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    @Test
    void listDirectory_firstUseOfAnUnpinnedMachine_pinsThePresentedHostKey() {
        // A machine may be browsed before it has ever had a terminal opened on it — Explorer must pin it
        // by the same trust-on-first-use rule, or it would connect to an unverified host every time.
        machineResolves("apalveien5", null);
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:fresh"));

        service.listDirectory(mid("apalveien5"), "/");

        verify(forTrackingHostKeys).pin(mid("apalveien5"), "SHA256:fresh");
    }

    @Test
    void listDirectory_anAlreadyPinnedMachine_isNotRepinned() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        service.listDirectory(mid("apalveien5"), "/");

        verify(forTrackingHostKeys, never()).pin(any(), any());
    }

    @Test
    void listDirectory_unknownMachine_propagatesNotFound() {
        // An id no machine in the fleet has. The resolver is the one that knows, so it is asked and refuses.
        when(forResolvingSshTargets.resolve(mid("ghost")))
            .thenThrow(new NotFoundException("Machine not found: " + mid("ghost")));

        assertThatThrownBy(() -> service.listDirectory(mid("ghost"), "/"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listDirectory_machineWithoutACredential_propagatesNoHostCredential() {
        when(forResolvingSshTargets.resolve(mid("apalveien5")))
            .thenThrow(new NoHostCredentialException("apalveien5"));

        assertThatThrownBy(() -> service.listDirectory(mid("apalveien5"), "/"))
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

        service.listDirectory(mid("NAS"), "/volume1/homes");

        // The browser's path is a TRUE coordinate. SFTP, inside the jail, has never heard of /volume1.
        verify(forBrowsingRemoteFiles).list(any(), eq("/homes"));
    }

    @Test
    void listDirectory_onAJailedMachine_anchorsTheEntriesBackOntoTheirTruePaths() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/homes", "geir", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("NAS"), "/volume1/homes");

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

        assertThat(service.listDirectory(mid("NAS"), "/volume1").root().path()).isEqualTo("/volume1");
    }

    @Test
    void listDirectory_withNoPath_beginsAtTheMachinesOwnRoot_notAtSlash() {
        machineIsJailedIn("NAS", "/volume1");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("NAS"), null);

        // Anything above the root is unreachable over SFTP, so the tree begins there — and asking for "/" on
        // this machine would be asking for a path it cannot answer at all.
        assertThat(directory.path()).isEqualTo("/volume1");
        verify(forBrowsingRemoteFiles).list(any(), eq("/"));
    }

    @Test
    void listDirectory_aPathOutsideTheJail_isRefusedWithTheReason_notAnsweredWithAnEmptyDirectory() {
        machineIsJailedIn("NAS", "/volume1");

        assertThatThrownBy(() -> service.listDirectory(mid("NAS"), "/volume2"))
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

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), "/home");

        // The regression that matters most: on a machine with no jail, every path is the path it always was.
        assertThat(directory.root()).isEqualTo(SftpRoot.NONE);
        assertThat(directory.root().path()).isEqualTo("/");
        assertThat(directory.path()).isEqualTo("/home");
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/home/geir");
        verify(forBrowsingRemoteFiles).list(any(), eq("/home"));
    }

    @Test
    void listDirectory_onAnUnprobeableMachine_leavesItsPathsAlone() {
        when(forResolvingSshTargets.resolve(mid("asleep"))).thenReturn(target("SHA256:pinned"));
        when(forResolvingSftpRoots.rootFor(any())).thenReturn(SftpRoot.NONE);
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        // A machine Vaier could not probe resolves to NONE, and NONE changes nothing. Unknown is safe.
        assertThat(service.listDirectory(mid("asleep"), "/etc").path()).isEqualTo("/etc");
        verify(forBrowsingRemoteFiles).list(any(), eq("/etc"));
    }

    @Test
    void listDirectory_aHostilePath_isRefusedBeforeTheMachineIsEvenProbed() {
        assertThatThrownBy(() -> service.listDirectory(mid("NAS"), "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The trust boundary still stands in front of everything: no SSH connection of any kind, not even the
        // two probes that learn the root.
        verify(forResolvingSftpRoots, never()).rootFor(any());
        verify(forResolvingSshTargets, never()).resolve(any());
    }

    // --- slice D: the past is a coordinate --------------------------------------------------------------
    //
    // With `at` naming an archive, the SAME browse mounts that archive on the machine and lists the same path
    // INSIDE it. The service asks the mount port for a mountpoint — it never learns what borg is — then maps
    // the archive path under the mountpoint and back, exactly as it maps a jail down and back for the present.

    private static final String MOUNTPOINT = "/home/ubuntu/.vaier-backup/mounts/ab12";

    private void archiveMountsAt(String machine, String archiveId, String mountpoint) {
        when(forMountingArchives.mount(mid(machine), archiveId)).thenReturn(new MountedArchive(mountpoint));
    }

    @Test
    void listDirectory_withAnArchive_listsThePathInsideTheMountedArchive() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in(MOUNTPOINT + "/home/geir", "notes.txt", false, 120, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), "/home/geir", "ab12");

        // The path the browser sent is an ARCHIVE coordinate; the SFTP read happens under the mountpoint.
        verify(forBrowsingRemoteFiles).list(any(), eq(MOUNTPOINT + "/home/geir"));
        // ...and the entries come back on their archive coordinates — the file's own path, not the mountpoint.
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/home/geir/notes.txt");
        assertThat(directory.path()).isEqualTo("/home/geir");
        // The listing carries the archive coordinate so the browser knows it is looking at the past.
        assertThat(directory.at()).isEqualTo("ab12");
    }

    @Test
    void listDirectory_withAnArchive_andNoPath_beginsAtTheArchiveRoot() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in(MOUNTPOINT, "home", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), null, "ab12");

        // The archive captured absolute machine paths, so its tree begins at "/", read at the mountpoint.
        verify(forBrowsingRemoteFiles).list(any(), eq(MOUNTPOINT));
        assertThat(directory.path()).isEqualTo("/");
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/home");
    }

    @Test
    void listDirectory_withAnArchive_stillRefusesAPathThatClimbsAboveTheRoot_beforeMounting() {
        // The trust boundary is unchanged in the past: a climb is refused before a machine is resolved or an
        // archive is mounted.
        assertThatThrownBy(() -> service.listDirectory(mid("apalveien5"), "/../../etc/passwd", "ab12"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forMountingArchives, never()).mount(any(), any());
        verify(forResolvingSshTargets, never()).resolve(any());
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    @Test
    void listDirectory_withNoArchive_neverMountsAnything_andReportsNoArchiveCoordinate() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory(mid("apalveien5"), "/home", null);

        // Omitting `at` is the present, unchanged (#326 is not regressed): no mount, no archive coordinate.
        verify(forMountingArchives, never()).mount(any(), any());
        assertThat(directory.at()).isNull();
        assertThat(directory.path()).isEqualTo("/home");
    }

    // --- slice 2: resolving a coordinate for the Transfer relay (the same mapping browsing uses) --------

    @Test
    void resolve_inThePresent_givesTheTarget_andTheJailMappedPath() {
        machineIsJailedIn("NAS", "/volume1");

        var resolved = service.resolve(mid("NAS"), "/volume1/homes/geir", null);

        // The browser's path is a TRUE coordinate; SFTP, inside the jail, must be asked for the jail path —
        // exactly what listDirectory maps a browse down to.
        assertThat(resolved.path()).isEqualTo("/homes/geir");
        assertThat(resolved.target().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void resolve_onAnUnjailedMachine_isTheTruePathItself() {
        machineResolves("apalveien5", "SHA256:pinned");

        assertThat(service.resolve(mid("apalveien5"), "/home/geir/notes.txt", null).path())
            .isEqualTo("/home/geir/notes.txt");
    }

    @Test
    void resolve_inThePast_mountsTheArchive_andMapsUnderTheMountpoint() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);

        var resolved = service.resolve(mid("apalveien5"), "/home/geir/notes.txt", "ab12");

        // A restore's source is the past: the read happens under the mountpoint, same as a past browse.
        assertThat(resolved.path()).isEqualTo(MOUNTPOINT + "/home/geir/notes.txt");
    }

    @Test
    void resolve_normalisesThePath_andRefusesAClimb_beforeAnyConnection() {
        assertThatThrownBy(() -> service.resolve(mid("apalveien5"), "/../../etc/passwd", null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forResolvingSshTargets, never()).resolve(any());
    }

    // --- slice 2: opening a file for download ----------------------------------------------------------

    @Test
    void openForDownload_givesTheFilenameSizeAndBytes_forAFile() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));
        // The writer opens the SFTP read lazily; here it writes a marker so we can prove it streamed.
        org.mockito.Mockito.doAnswer(inv -> {
            ((java.io.OutputStream) inv.getArgument(2)).write("payload".getBytes());
            return null;
        }).when(forBrowsingRemoteFiles).download(any(), eq("/home/geir/notes.txt"), any());

        var download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", null);

        assertThat(download.filename()).isEqualTo("notes.txt");
        assertThat(download.sizeBytes()).isEqualTo(120);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        download.writer().accept(out);
        assertThat(out.toString()).isEqualTo("payload");
    }

    @Test
    void openForDownload_givesAnOctetStreamContentType_forAFile() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));

        var download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", null);

        assertThat(download.contentType()).isEqualTo("application/octet-stream");
    }

    // --- slice 2 follow-up: a directory downloads as a zip of its whole tree -----------------------------

    @Test
    void openForDownload_ofADirectory_zipsTheWholeTree_withEntryNamesRelativeToTheDirectory() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));
        // The single-connection walk hands each file's bytes to the visitor, named relative to the root; the
        // service turns them into zip entries. That the walk holds one connection is the adapter's contract.
        walksTree("/home/geir", v -> {
            v.file("notes.txt", stream("top-lvl"));
            v.file("docs/readme.md", stream("nested"));
        });

        Download download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir", null);

        assertThat(download.filename()).isEqualTo("geir.zip");
        assertThat(download.contentType()).isEqualTo("application/zip");
        // A zip's byte count isn't known until it's built, and isn't the sum of the files it holds.
        assertThat(download.sizeBytes()).isEqualTo(-1);
        assertThat(unzip(download)).containsOnly(
            entry("notes.txt", "top-lvl"),
            entry("docs/readme.md", "nested"));
    }

    @Test
    void openForDownload_ofADirectory_makesAnEmptySubdirectory_aZipDirectoryEntry() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));
        // The walk reports an empty directory as itself — the only way a zip can carry a folder with nothing
        // in it — and the service maps that to a zip directory entry.
        walksTree("/home/geir", v -> v.directory("empty"));

        Download download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir", null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        download.writer().accept(out);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry only = zip.getNextEntry();
            assertThat(only.getName()).isEqualTo("empty/");
            assertThat(only.isDirectory()).isTrue();
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void openForDownload_ofTheRootDirectory_fallsBackToTheMachineName_forTheZipFilename() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));

        Download download = service.openForDownload(mid("apalveien5"), "apalveien5", "/", null);

        // "/" has no basename of its own to zip under, so the machine's own name stands in for it. The walk
        // itself is lazy (only the writer opens it), so nothing about listing is stubbed or verified here.
        assertThat(download.filename()).isEqualTo("apalveien5.zip");
    }

    @Test
    void openForDownload_ofADirectory_inThePast_zipsTheArchivedTree() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        when(forBrowsingRemoteFiles.stat(any(), eq(MOUNTPOINT + "/home/geir")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));
        // The past reads under the mountpoint, so the walk is rooted there — same rule as a single-file
        // download; skipping an unreadable file is the walk's own concern (proven in the adapter test).
        walksTree(MOUNTPOINT + "/home/geir", v -> v.file("notes.txt", stream("old")));

        // A download is a read, so zipping the past is fine too — same rule as a single-file download.
        Download download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir", "ab12");

        assertThat(unzip(download)).containsOnly(entry("notes.txt", "old"));
    }

    // --- selection zip: download a fleet-wide selection of coordinates as one zip ----------------------

    private static Selection.Coordinate coordinate(String machine, String path, String at) {
        return new Selection.Coordinate(mid(machine), machine, path, at);
    }

    /** Stub a file coordinate: it stats as a file, and its download writes {@code content} into the stream. */
    private void fileAt(String path, String content) {
        when(forBrowsingRemoteFiles.stat(any(), eq(path)))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, content.length()));
        doAnswer(inv -> {
            ((java.io.OutputStream) inv.getArgument(2)).write(content.getBytes());
            return null;
        }).when(forBrowsingRemoteFiles).download(any(), eq(path), any());
    }

    @Test
    void openForDownload_ofASingleFileSelection_zipsItByBasename() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        fileAt("/home/geir/notes.txt", "top-lvl");

        Download download = service.openForDownload(List.of(coordinate("apalveien5", "/home/geir/notes.txt", null)));

        assertThat(download.contentType()).isEqualTo("application/zip");
        assertThat(download.sizeBytes()).isEqualTo(-1);
        assertThat(unzip(download)).containsOnly(entry("notes.txt", "top-lvl"));
    }

    @Test
    void openForDownload_ofASingleDirectorySelection_zipsItsSubtreeUnderItsBasename() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));
        walksTree("/home/geir", v -> {
            v.file("notes.txt", stream("top-lvl"));
            v.file("docs/readme.md", stream("nested"));
        });

        Download download = service.openForDownload(List.of(coordinate("apalveien5", "/home/geir", null)));

        // A directory coordinate's whole subtree sits under its basename — unlike a single-directory download,
        // where the folder's own name is dropped, here it must be kept so the selection stays unambiguous.
        assertThat(unzip(download)).containsOnly(
            entry("geir/notes.txt", "top-lvl"),
            entry("geir/docs/readme.md", "nested"));
    }

    @Test
    void openForDownload_ofSeveralItemsOnOneMachine_areTopLevelEntriesByBasename() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        fileAt("/home/geir/notes.txt", "notes");
        fileAt("/etc/hosts", "hosts");

        Download download = service.openForDownload(List.of(
            coordinate("apalveien5", "/home/geir/notes.txt", null),
            coordinate("apalveien5", "/etc/hosts", null)));

        assertThat(download.filename()).isEqualTo("apalveien5.zip");
        assertThat(unzip(download)).containsOnly(
            entry("notes.txt", "notes"),
            entry("hosts", "hosts"));
    }

    @Test
    void openForDownload_spanningMachines_prefixesEveryEntryByItsMachine() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        machineResolves("colina27", "SHA256:pinned2");
        fileAt("/etc/hosts", "hosts");

        Download download = service.openForDownload(List.of(
            coordinate("apalveien5", "/etc/hosts", null),
            coordinate("colina27", "/etc/hosts", null)));

        // Two machines' /etc/hosts do not collide: each lives under its own machine folder.
        assertThat(download.filename()).isEqualTo("vaier-selection.zip");
        assertThat(unzip(download)).containsOnly(
            entry("apalveien5/hosts", "hosts"),
            entry("colina27/hosts", "hosts"));
    }

    @Test
    void openForDownload_basenameCollisionOnOneMachine_deDupsWithASuffix() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        fileAt("/a/config.yml", "first");
        fileAt("/b/config.yml", "second");

        Download download = service.openForDownload(List.of(
            coordinate("apalveien5", "/a/config.yml", null),
            coordinate("apalveien5", "/b/config.yml", null)));

        // Neither is silently overwritten — the second colliding basename gets a " (2)" suffix.
        assertThat(unzip(download)).containsOnly(
            entry("config.yml", "first"),
            entry("config.yml (2)", "second"));
    }

    @Test
    void openForDownload_ofASelection_acceptsAnArchiveCoordinate_readUnderTheMountpoint() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        fileAt(MOUNTPOINT + "/home/geir/notes.txt", "old");

        // A download is a read, so a coordinate's `at` may name an archive — resolved under the mountpoint,
        // exactly as a single download's past is.
        Download download = service.openForDownload(List.of(
            coordinate("apalveien5", "/home/geir/notes.txt", "ab12")));

        assertThat(unzip(download)).containsOnly(entry("notes.txt", "old"));
    }

    @Test
    void openForDownload_ofASelection_namesTheZipByMachineOrSelection_withoutOpeningAnyConnection() {
        // The filename is the selection's decision — machine names alone — so it is known before a single
        // coordinate is resolved, stat'd or streamed. No port is touched here.
        assertThat(service.openForDownload(List.of(
            coordinate("apalveien5", "/a", null),
            coordinate("apalveien5", "/b", null))).filename()).isEqualTo("apalveien5.zip");
        assertThat(service.openForDownload(List.of(
            coordinate("apalveien5", "/a", null),
            coordinate("colina27", "/b", null))).filename()).isEqualTo("vaier-selection.zip");
    }

    /** Drives the mocked single-connection tree walk: the {@code emit} lambda feeds entries to the visitor. */
    @FunctionalInterface
    interface TreeWalk {
        void emit(ForBrowsingRemoteFiles.RemoteTreeVisitor visitor) throws IOException;
    }

    private void walksTree(String rootPath, TreeWalk walk) {
        doAnswer(inv -> {
            walk.emit(inv.getArgument(2));
            return null;
        }).when(forBrowsingRemoteFiles).walkTree(any(), eq(rootPath), any());
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes());
    }

    private static Map<String, String> unzip(Download download) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        download.writer().accept(out);
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    entries.put(e.getName(), new String(zip.readAllBytes()));
                }
            }
        }
        return entries;
    }

    // --- slice 5: deleting a file or directory (present-only, destructive) -----------------------------

    @Test
    void delete_resolvesTheMachine_andDeletesTheRequestedPath() {
        machineResolves("apalveien5", "SHA256:pinned");

        service.delete(mid("apalveien5"), "/home/geir/old");

        verify(forBrowsingRemoteFiles).delete(any(), eq("/home/geir/old"));
    }

    @Test
    void delete_onAJailedMachine_deletesTheJailPath_ofTheTruePathTheBrowserSent() {
        machineIsJailedIn("NAS", "/volume1");

        service.delete(mid("NAS"), "/volume1/homes/geir/old");

        // The browser's path is a TRUE coordinate; SFTP, inside the jail, must be asked for the jail path —
        // the same down-mapping every other Explorer operation shares.
        verify(forBrowsingRemoteFiles).delete(any(), eq("/homes/geir/old"));
    }

    @Test
    void delete_refusesToDeleteTheMachinesSftpRootItself_beforeAnyConnection() {
        machineIsJailedIn("NAS", "/volume1");

        assertThatThrownBy(() -> service.delete(mid("NAS"), "/volume1"))
            .isInstanceOf(CannotDeleteSftpRootException.class)
            .hasMessageContaining("/volume1");

        // Deleting the whole browsable tree is a domain refusal — the machine is never even asked.
        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_refusesToDeleteTheFilesystemRoot_onAnUnjailedMachine() {
        machineResolves("apalveien5", "SHA256:pinned");

        assertThatThrownBy(() -> service.delete(mid("apalveien5"), "/"))
            .isInstanceOf(CannotDeleteSftpRootException.class);

        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_aPathClimbingAboveTheRoot_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.delete(mid("apalveien5"), "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The trust boundary stands in front of a delete exactly as it does a browse: no machine is resolved.
        verify(forResolvingSshTargets, never()).resolve(any());
        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_aRelativePath_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.delete(mid("apalveien5"), "etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void openForDownload_inThePast_resolvesUnderTheMountpoint() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        when(forBrowsingRemoteFiles.stat(any(), eq(MOUNTPOINT + "/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));

        var download = service.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", "ab12");

        // A download is a read, so the past is fine — the file is read under the mountpoint.
        assertThat(download.filename()).isEqualTo("notes.txt");
        verify(forBrowsingRemoteFiles).stat(any(), eq(MOUNTPOINT + "/home/geir/notes.txt"));
    }

    // --- Open: handing a viewable file to the browser to display ----------------------------------------

    @Test
    void openForView_ofAViewableFile_carriesItsMediaTypeSizeAndBytes() throws Exception {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/holiday.png")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 4242));
        doAnswer(inv -> {
            ((OutputStream) inv.getArgument(2)).write("PNGBYTES".getBytes());
            return null;
        }).when(forBrowsingRemoteFiles).download(any(), eq("/home/geir/holiday.png"), any());

        View view = service.openForView(mid("apalveien5"), "/home/geir/holiday.png", null);

        assertThat(view.filename()).isEqualTo("holiday.png");
        assertThat(view.sizeBytes()).isEqualTo(4242);
        // The media type is the domain's (ViewableFile), never octet-stream — that is what makes it render.
        assertThat(view.mediaType()).isEqualTo("image/png");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        view.writer().accept(out);
        assertThat(out.toString()).isEqualTo("PNGBYTES");
    }

    @Test
    void openForView_ofATextishFile_servesItAsPlainText() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/etc/docker-compose.yml")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 90));

        View view = service.openForView(mid("apalveien5"), "/etc/docker-compose.yml", null);

        assertThat(view.mediaType()).isEqualTo("text/plain;charset=utf-8");
    }

    @Test
    void openForView_carriesTheContentSecurityPolicyTheDomainChose() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/holiday.png")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 10));

        View view = service.openForView(mid("apalveien5"), "/home/geir/holiday.png", null);

        // The policy is not the controller's to invent: it differs per media type (a PDF cannot be sandboxed
        // and still render), so it travels from the domain with the file.
        assertThat(view.contentSecurityPolicy())
            .isEqualTo(ViewableFile.require("holiday.png", false).contentSecurityPolicy());
    }

    @Test
    void openForView_ofSomethingNotViewable_isRefused_andNeverServedAnyway() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/srv/www/index.html")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 500));

        // Inline HTML from a fleet machine runs script against the operator's Vaier session. Refused, never
        // quietly served under some other type.
        assertThatThrownBy(() -> service.openForView(mid("apalveien5"), "/srv/www/index.html", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("index.html");
        verify(forBrowsingRemoteFiles, never()).download(any(), any(), any());
    }

    @Test
    void openForView_ofADirectory_isRefused() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/photos.png")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(true, 4096));

        // A folder named like an image is still a folder: nothing to render, and a zip when downloaded.
        assertThatThrownBy(() -> service.openForView(mid("apalveien5"), "/home/geir/photos.png", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openForView_inThePast_readsUnderTheMountpoint_becauseAViewIsARead() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        when(forBrowsingRemoteFiles.stat(any(), eq(MOUNTPOINT + "/home/geir/holiday.png")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 4242));

        View view = service.openForView(mid("apalveien5"), "/home/geir/holiday.png", "ab12");

        assertThat(view.filename()).isEqualTo("holiday.png");
        assertThat(view.mediaType()).isEqualTo("image/png");
        verify(forBrowsingRemoteFiles).stat(any(), eq(MOUNTPOINT + "/home/geir/holiday.png"));
    }

    @Test
    void openForView_appliesThePathTrustBoundary_beforeTouchingAnyMachine() {
        assertThatThrownBy(() -> service.openForView(mid("apalveien5"), "/../etc/shadow", null))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forResolvingSshTargets, never()).resolve(any());
    }

    // --- uploading a file into a machine's directory ---------------------------------------------------
    //
    // The service orchestrates and nothing more: resolve the machine, resolve its SFTP root, hand both plus
    // the port to the Upload. Every decision — where the file lands, whether the name is taken, whether it may
    // be replaced — is the domain's (UploadTest covers them); these prove the wiring and that the refusals
    // really do reach the caller through the service.

    private void nameIsFree(String jailPath) {
        when(forBrowsingRemoteFiles.stat(any(), eq(jailPath)))
            .thenThrow(new NotFoundException("No such directory: " + jailPath));
    }

    @Test
    void upload_resolvesTheMachine_andStreamsTheContentToTheDestination() {
        machineResolves("apalveien5", "SHA256:pinned");
        nameIsFree("/home/geir/notes.txt");
        InputStream content = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));

        service.upload(Upload.into(mid("apalveien5"), "/home/geir", "notes.txt", false), content);

        ArgumentCaptor<SshTarget> resolved = ArgumentCaptor.forClass(SshTarget.class);
        verify(forBrowsingRemoteFiles).upload(resolved.capture(), eq("/home/geir/notes.txt"), eq(content));
        assertThat(resolved.getValue().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void upload_onAJailedMachine_writesAtTheJailPath_ofTheTrueDirectoryTheBrowserSent() {
        machineIsJailedIn("NAS", "/volume1");
        nameIsFree("/homes/geir/notes.txt");

        service.upload(Upload.into(mid("NAS"), "/volume1/homes/geir", "notes.txt", false),
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        verify(forBrowsingRemoteFiles).upload(any(), eq("/homes/geir/notes.txt"), any());
    }

    @Test
    void upload_ontoANameAlreadyTaken_isAConflict_andNothingIsWritten() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));

        assertThatThrownBy(() -> service.upload(
            Upload.into(mid("apalveien5"), "/home/geir", "notes.txt", false),
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(ConflictException.class);

        verify(forBrowsingRemoteFiles, never()).upload(any(), any(), any());
    }

    @Test
    void upload_ontoANameAlreadyTaken_withOverwriteAsked_writesOverIt() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forBrowsingRemoteFiles.stat(any(), eq("/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));

        service.upload(Upload.into(mid("apalveien5"), "/home/geir", "notes.txt", true),
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        verify(forBrowsingRemoteFiles).upload(any(), eq("/home/geir/notes.txt"), any());
    }

    @Test
    void upload_toAPathAboveTheMachinesSftpRoot_isRefused_andNothingIsWritten() {
        machineIsJailedIn("NAS", "/volume1");

        assertThatThrownBy(() -> service.upload(
            Upload.into(mid("NAS"), "/etc", "notes.txt", false),
            new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(PathOutsideSftpRootException.class);

        verify(forBrowsingRemoteFiles, never()).upload(any(), any(), any());
    }

    // --- a bundle, offered and opened (#360) ---------------------------------------------------------

    private static final MachineId NAS = MachineId.of("41a14c07-b2b9-4e6f-bb48-3991a11bb862");

    /** Every path is stat'd before the offer, so a path that is not there is refused now, not at download. */
    @Test
    void offer_statsEveryPath_andHoldsTheSizedBundle() {
        SshTarget target = mock(SshTarget.class);
        when(forResolvingSshTargets.resolve(NAS)).thenReturn(target);
        when(forResolvingSftpRoots.rootFor(target)).thenReturn(SftpRoot.NONE);
        when(forBrowsingRemoteFiles.stat(target, "/volume1/photo/a.jpg")).thenReturn(new RemoteStat(false, 1_000_000));
        when(forBrowsingRemoteFiles.stat(target, "/volume1/photo/b.jpg")).thenReturn(new RemoteStat(false, 2_000_000));

        Bundle bundle = service.offer(NAS, "NAS", List.of("/volume1/photo/a.jpg", "/volume1/photo/b.jpg"), "pictures");

        assertThat(bundle.name()).isEqualTo("pictures.zip");
        assertThat(bundle.describe()).isEqualTo("2 files, 3.0 MB");
        verify(forHoldingBundles).hold(bundle);
    }

    @Test
    void offer_refusesAPathThatIsNotThere_namingIt() {
        SshTarget target = mock(SshTarget.class);
        when(forResolvingSshTargets.resolve(NAS)).thenReturn(target);
        when(forResolvingSftpRoots.rootFor(target)).thenReturn(SftpRoot.NONE);
        when(forBrowsingRemoteFiles.stat(target, "/volume1/photo/gone.jpg"))
            .thenThrow(new NotFoundException("No such file: /volume1/photo/gone.jpg"));

        assertThatThrownBy(() -> service.offer(NAS, "NAS", List.of("/volume1/photo/gone.jpg"), "x"))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("/volume1/photo/gone.jpg is not on NAS.");
        verify(forHoldingBundles, never()).hold(any());
    }

    /** Opening a bundle is the selection zip under the bundle's own name; a gone bundle is a 404 in words. */
    @Test
    void open_isTheSelectionZipUnderTheBundlesName() {
        Bundle bundle = Bundle.offer(NAS, "NAS", List.of("/volume1/photo/a.jpg"), "pictures", System.currentTimeMillis());
        when(forHoldingBundles.find(bundle.id())).thenReturn(Optional.of(bundle));

        Download download = service.open(bundle.id());

        assertThat(download.filename()).isEqualTo("pictures.zip");
        assertThat(download.contentType()).isEqualTo("application/zip");
        assertThat(download.sizeBytes()).isEqualTo(-1);
    }

    @Test
    void open_refusesABundleThatIsGoneOrExpired() {
        when(forHoldingBundles.find("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open("gone"))
            .isInstanceOf(NotFoundException.class).hasMessage("That download is gone; ask again.");

        Bundle stale = Bundle.offer(NAS, "NAS", List.of("/a"), "x", System.currentTimeMillis() - Bundle.TTL.toMillis() - 1);
        when(forHoldingBundles.find(stale.id())).thenReturn(Optional.of(stale));
        assertThatThrownBy(() -> service.open(stale.id()))
            .isInstanceOf(NotFoundException.class).hasMessage("That download has expired; ask again.");
    }
}
