package net.vaier.application.service;

import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CannotDeleteSftpRootException;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostCredential;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.Selection;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import net.vaier.domain.SourcePaths;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    @Mock ForMountingArchives forMountingArchives;
    @Mock ForReadingProtectedPaths forReadingProtectedPaths;

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
    void listDirectory_carriesTheMachinesProtectedPaths_soEntriesCanBeMarkedBackedUp() {
        machineResolves("apalveien5", "SHA256:pinned");
        when(forReadingProtectedPaths.protectedPathsFor("apalveien5"))
            .thenReturn(SourcePaths.of(List.of("/home/geir")));
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("apalveien5", "/home/geir");

        // The service asks the driven port (never a backup use case) and threads the domain value object back;
        // the coverage decision itself stays in the domain.
        assertThat(directory.protectedPaths().covers("/home/geir/docs")).isTrue();
        verify(forReadingProtectedPaths).protectedPathsFor("apalveien5");
    }

    @Test
    void listDirectory_inThePast_doesNotConsultProtectedPaths_andMarksNothing() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in(MOUNTPOINT + "/home/geir", "docs", true, 4096, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("apalveien5", "/home/geir", "ab12");

        // The past's backup shape is not today's — the protected set is empty and the port is never asked.
        assertThat(directory.protectedPaths().isEmpty()).isTrue();
        verify(forReadingProtectedPaths, never()).protectedPathsFor(any());
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

    // --- slice D: the past is a coordinate --------------------------------------------------------------
    //
    // With `at` naming an archive, the SAME browse mounts that archive on the machine and lists the same path
    // INSIDE it. The service asks the mount port for a mountpoint — it never learns what borg is — then maps
    // the archive path under the mountpoint and back, exactly as it maps a jail down and back for the present.

    private static final String MOUNTPOINT = "/home/ubuntu/.vaier-backup/mounts/ab12";

    private void archiveMountsAt(String machine, String archiveId, String mountpoint) {
        when(forMountingArchives.mount(machine, archiveId)).thenReturn(new MountedArchive(mountpoint));
    }

    @Test
    void listDirectory_withAnArchive_listsThePathInsideTheMountedArchive() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        remoteAnswers(new DirectoryListing(List.of(
            FileEntry.in(MOUNTPOINT + "/home/geir", "notes.txt", false, 120, WHEN)), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("apalveien5", "/home/geir", "ab12");

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

        MachineDirectory directory = service.listDirectory("apalveien5", null, "ab12");

        // The archive captured absolute machine paths, so its tree begins at "/", read at the mountpoint.
        verify(forBrowsingRemoteFiles).list(any(), eq(MOUNTPOINT));
        assertThat(directory.path()).isEqualTo("/");
        assertThat(directory.entries()).extracting(FileEntry::path).containsExactly("/home");
    }

    @Test
    void listDirectory_withAnArchive_stillRefusesAPathThatClimbsAboveTheRoot_beforeMounting() {
        // The trust boundary is unchanged in the past: a climb is refused before a machine is resolved or an
        // archive is mounted.
        assertThatThrownBy(() -> service.listDirectory("apalveien5", "/../../etc/passwd", "ab12"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forMountingArchives, never()).mount(any(), any());
        verify(forResolvingSshTargets, never()).resolve(any());
        verify(forBrowsingRemoteFiles, never()).list(any(), any());
    }

    @Test
    void listDirectory_withNoArchive_neverMountsAnything_andReportsNoArchiveCoordinate() {
        machineResolves("apalveien5", "SHA256:pinned");
        remoteAnswers(new DirectoryListing(List.of(), "SHA256:pinned"));

        MachineDirectory directory = service.listDirectory("apalveien5", "/home", null);

        // Omitting `at` is the present, unchanged (#326 is not regressed): no mount, no archive coordinate.
        verify(forMountingArchives, never()).mount(any(), any());
        assertThat(directory.at()).isNull();
        assertThat(directory.path()).isEqualTo("/home");
    }

    // --- slice 2: resolving a coordinate for the Transfer relay (the same mapping browsing uses) --------

    @Test
    void resolve_inThePresent_givesTheTarget_andTheJailMappedPath() {
        machineIsJailedIn("NAS", "/volume1");

        var resolved = service.resolve("NAS", "/volume1/homes/geir", null);

        // The browser's path is a TRUE coordinate; SFTP, inside the jail, must be asked for the jail path —
        // exactly what listDirectory maps a browse down to.
        assertThat(resolved.path()).isEqualTo("/homes/geir");
        assertThat(resolved.target().host()).isEqualTo("10.13.13.6");
    }

    @Test
    void resolve_onAnUnjailedMachine_isTheTruePathItself() {
        machineResolves("apalveien5", "SHA256:pinned");

        assertThat(service.resolve("apalveien5", "/home/geir/notes.txt", null).path())
            .isEqualTo("/home/geir/notes.txt");
    }

    @Test
    void resolve_inThePast_mountsTheArchive_andMapsUnderTheMountpoint() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);

        var resolved = service.resolve("apalveien5", "/home/geir/notes.txt", "ab12");

        // A restore's source is the past: the read happens under the mountpoint, same as a past browse.
        assertThat(resolved.path()).isEqualTo(MOUNTPOINT + "/home/geir/notes.txt");
    }

    @Test
    void resolve_normalisesThePath_andRefusesAClimb_beforeAnyConnection() {
        assertThatThrownBy(() -> service.resolve("apalveien5", "/../../etc/passwd", null))
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

        var download = service.openForDownload("apalveien5", "/home/geir/notes.txt", null);

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

        var download = service.openForDownload("apalveien5", "/home/geir/notes.txt", null);

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

        Download download = service.openForDownload("apalveien5", "/home/geir", null);

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

        Download download = service.openForDownload("apalveien5", "/home/geir", null);

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

        Download download = service.openForDownload("apalveien5", "/", null);

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
        Download download = service.openForDownload("apalveien5", "/home/geir", "ab12");

        assertThat(unzip(download)).containsOnly(entry("notes.txt", "old"));
    }

    // --- selection zip: download a fleet-wide selection of coordinates as one zip ----------------------

    private static Selection.Coordinate coordinate(String machine, String path, String at) {
        return new Selection.Coordinate(machine, path, at);
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

        service.delete("apalveien5", "/home/geir/old");

        verify(forBrowsingRemoteFiles).delete(any(), eq("/home/geir/old"));
    }

    @Test
    void delete_onAJailedMachine_deletesTheJailPath_ofTheTruePathTheBrowserSent() {
        machineIsJailedIn("NAS", "/volume1");

        service.delete("NAS", "/volume1/homes/geir/old");

        // The browser's path is a TRUE coordinate; SFTP, inside the jail, must be asked for the jail path —
        // the same down-mapping every other Explorer operation shares.
        verify(forBrowsingRemoteFiles).delete(any(), eq("/homes/geir/old"));
    }

    @Test
    void delete_refusesToDeleteTheMachinesSftpRootItself_beforeAnyConnection() {
        machineIsJailedIn("NAS", "/volume1");

        assertThatThrownBy(() -> service.delete("NAS", "/volume1"))
            .isInstanceOf(CannotDeleteSftpRootException.class)
            .hasMessageContaining("/volume1");

        // Deleting the whole browsable tree is a domain refusal — the machine is never even asked.
        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_refusesToDeleteTheFilesystemRoot_onAnUnjailedMachine() {
        machineResolves("apalveien5", "SHA256:pinned");

        assertThatThrownBy(() -> service.delete("apalveien5", "/"))
            .isInstanceOf(CannotDeleteSftpRootException.class);

        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_aPathClimbingAboveTheRoot_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.delete("apalveien5", "/../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        // The trust boundary stands in front of a delete exactly as it does a browse: no machine is resolved.
        verify(forResolvingSshTargets, never()).resolve(any());
        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void delete_aRelativePath_isRefusedBeforeAnyConnection() {
        assertThatThrownBy(() -> service.delete("apalveien5", "etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(forBrowsingRemoteFiles, never()).delete(any(), any());
    }

    @Test
    void openForDownload_inThePast_resolvesUnderTheMountpoint() {
        machineResolves("apalveien5", "SHA256:pinned");
        archiveMountsAt("apalveien5", "ab12", MOUNTPOINT);
        when(forBrowsingRemoteFiles.stat(any(), eq(MOUNTPOINT + "/home/geir/notes.txt")))
            .thenReturn(new ForBrowsingRemoteFiles.RemoteStat(false, 120));

        var download = service.openForDownload("apalveien5", "/home/geir/notes.txt", "ab12");

        // A download is a read, so the past is fine — the file is read under the mountpoint.
        assertThat(download.filename()).isEqualTo("notes.txt");
        verify(forBrowsingRemoteFiles).stat(any(), eq(MOUNTPOINT + "/home/geir/notes.txt"));
    }
}
