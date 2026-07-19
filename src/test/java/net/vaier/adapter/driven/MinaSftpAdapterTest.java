package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The SFTP adapter against a real in-JVM SSH server running the SFTP subsystem — so the connect, the
 * authentication and the host-key trust-on-first-use are genuinely exercised, not mocked.
 */
class MinaSftpAdapterTest {

    private final MinaSftpAdapter adapter = new MinaSftpAdapter();
    private SshServer server;

    @TempDir Path remoteRoot;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(true);
    }

    private int startServer() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setPasswordAuthenticator((u, p, s) -> "test".equals(u) && "secret".equals(p));
        server.start();
        return server.getPort();
    }

    /** The NAS's shape: an SFTP subsystem chrooted into {@code remoteRoot}, with no jail on the exec channel. */
    private int startChrootedServer() throws Exception {
        int port = startServer();
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot.toAbsolutePath()));
        return port;
    }

    private SshTarget target(int port, String password, String pinnedFingerprint) {
        return new SshTarget("localhost", port, "test", AuthMethod.PASSWORD, password, null, pinnedFingerprint);
    }

    private SshTarget target(int port) {
        return target(port, "secret", null);
    }

    /** The temp dir is the server's real filesystem root here, so remote paths are absolute host paths. */
    private String remote(String relative) {
        return remoteRoot.resolve(relative).toAbsolutePath().toString();
    }

    /**
     * The adapter closes its side of the connection synchronously, but the server drops the session from its
     * active set asynchronously as it processes the disconnect — so assert the session is gone within a short
     * window, not in the same instant the call returns. Otherwise the check races the server-side teardown.
     */
    private void assertServerHasNoActiveSessions() throws InterruptedException {
        for (int i = 0; i < 100 && !server.getActiveSessions().isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertThat(server.getActiveSessions()).isEmpty();
    }

    @Test
    void listsADirectory_reportingNamesPathsKindsAndSizes() throws Exception {
        int port = startServer();
        Files.writeString(remoteRoot.resolve("notes.txt"), "hello sftp");
        Files.createDirectory(remoteRoot.resolve("media"));

        DirectoryListing listing = adapter.list(target(port), remote(""));

        assertThat(listing.entries()).extracting(FileEntry::name)
            .containsExactlyInAnyOrder("notes.txt", "media");
        FileEntry notes = listing.entries().stream().filter(e -> e.name().equals("notes.txt")).findFirst().orElseThrow();
        assertThat(notes.directory()).isFalse();
        assertThat(notes.sizeBytes()).isEqualTo("hello sftp".getBytes(StandardCharsets.UTF_8).length);
        assertThat(notes.path()).isEqualTo(remote("notes.txt"));
        assertThat(notes.modified()).isNotNull();

        FileEntry media = listing.entries().stream().filter(e -> e.name().equals("media")).findFirst().orElseThrow();
        assertThat(media.directory()).isTrue();
    }

    @Test
    void dropsTheDotAndDotDotProtocolEntries() throws Exception {
        int port = startServer();
        Files.writeString(remoteRoot.resolve("only.txt"), "x");

        DirectoryListing listing = adapter.list(target(port), remote(""));

        // readdir always answers "." and ".." — they are SFTP protocol artifacts, not files in the tree.
        assertThat(listing.entries()).extracting(FileEntry::name).containsExactly("only.txt");
    }

    @Test
    void anEmptyDirectory_listsAsNothing() throws Exception {
        int port = startServer();
        Files.createDirectory(remoteRoot.resolve("empty"));

        assertThat(adapter.list(target(port), remote("empty")).entries()).isEmpty();
    }

    @Test
    void firstConnect_reportsTheHostKeyFingerprint_soTheCallerCanPinIt() throws Exception {
        int port = startServer();

        DirectoryListing listing = adapter.list(target(port), remote(""));

        assertThat(listing.hostKeyFingerprint()).startsWith("SHA256:");
    }

    @Test
    void aPinnedFingerprintThatNoLongerMatches_throwsHostKeyMismatch() throws Exception {
        int port = startServer();

        assertThatThrownBy(() -> adapter.list(target(port, "secret", "SHA256:not-this-host"), remote("")))
            .isInstanceOf(HostKeyMismatchException.class);
    }

    @Test
    void wrongPassword_throwsSshAuthException() throws Exception {
        int port = startServer();

        assertThatThrownBy(() -> adapter.list(target(port, "wrong", null), remote("")))
            .isInstanceOf(SshAuthException.class);
    }

    @Test
    void aPathThatIsNotThere_throwsNotFound_carryingThePath() throws Exception {
        // Browsing a directory that isn't there is an ordinary outcome — a stale link, a volume that moved —
        // not a failure of Vaier. Reporting it as a transport error would surface as a 500 and read to the
        // operator as "Vaier is broken" rather than "that folder is gone".
        int port = startServer();

        assertThatThrownBy(() -> adapter.list(target(port), remote("no-such-dir")))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("no-such-dir");
    }

    @Test
    void aDirectoryTheSshUserCannotRead_throwsPermissionDenied_carryingThePath() throws Exception {
        // The common case on a real fleet: the SSH user is not root, so whole swathes of / are unreadable.
        // That is a fact about the machine, not an error in Vaier, and it must not read as one.
        Path locked = Files.createDirectory(remoteRoot.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "running as root — cannot make a directory unreadable");
        int port = startServer();

        try {
            assertThatThrownBy(() -> adapter.list(target(port), remote("locked")))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("locked");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // --- where the SFTP subsystem thinks the filesystem begins (#326) ---------------------------------

    @Test
    void home_reportsThePathTheSftpChannelCanonicalisesDotTo() throws Exception {
        int port = startServer();

        // Half of the pair of answers SftpRoot is resolved from: what SFTP calls the SSH user's home. On an
        // unjailed server that is a real absolute path on the machine, and the exec channel would say the same.
        assertThat(adapter.home(target(port))).startsWith("/");
    }

    @Test
    void home_onAChrootedSftpSubsystem_reportsTheHomeAsTheJailSeesIt_notAsTheMachineDoes() throws Exception {
        // The NAS, reproduced: DSM chroots its SFTP subsystem to /volume1 while leaving the exec channel on
        // the real root. Here the jail is the temp dir, so SFTP calls the user's home "/" where the machine
        // itself calls it <tempdir>. Those two answers are exactly what SftpRoot.resolve takes the difference
        // of — and without this one, Vaier has no way to ask the jailed half of the machine anything.
        int port = startChrootedServer();
        Files.createDirectory(remoteRoot.resolve("homes"));

        assertThat(adapter.home(target(port))).isEqualTo("/");

        // and the listing is jail-relative too — /homes here, not <tempdir>/homes. This is the bug #326 fixes:
        // the same directory has two coordinates, and only the exec channel's one is the machine's own.
        assertThat(adapter.list(target(port), "/").entries()).extracting(FileEntry::path).containsExactly("/homes");
    }

    @Test
    void firstDirectory_findsTheHomeInsideTheJail_whenTheJailWillNotSayWhereItIs() throws Exception {
        // The NAS's real shape, and the reason home() alone is not enough: a chrooted subsystem canonicalises
        // "." to "/" — the jail root itself — which says nothing about where that root is on the machine. So
        // the home is *found* instead: the machine says its home is physically <tempdir>/homes/geir, and the
        // jail is asked which of that path's names it can see. It sees /homes/geir, and the difference is the
        // jail.
        int port = startChrootedServer();
        Files.createDirectories(remoteRoot.resolve("homes/geir"));
        String trueHome = remoteRoot.toAbsolutePath() + "/homes/geir";

        assertThat(adapter.firstDirectory(target(port), SftpRoot.jailCandidates(trueHome)))
            .contains("/homes/geir");

        // And that is exactly what the domain takes the difference of.
        assertThat(SftpRoot.resolve(trueHome, "/homes/geir").orElseThrow().path())
            .isEqualTo(remoteRoot.toAbsolutePath().toString());
    }

    @Test
    void firstDirectory_onAnUnjailedServer_matchesTheTrueHomeItself_soNothingIsEverInvented() throws Exception {
        // The safety property, end to end: with no jail, the very first candidate — the machine's own home —
        // is visible, so the search stops there and resolves to NONE. An ordinary machine cannot be handed a
        // jail it does not have.
        int port = startServer();
        Files.createDirectories(remoteRoot.resolve("homes/geir"));
        String trueHome = remoteRoot.toAbsolutePath() + "/homes/geir";

        assertThat(adapter.firstDirectory(target(port), SftpRoot.jailCandidates(trueHome))).contains(trueHome);
        assertThat(SftpRoot.resolve(trueHome, trueHome).orElseThrow()).isEqualTo(SftpRoot.NONE);
    }

    @Test
    void firstDirectory_whenTheJailCanSeeNoneOfThem_answersNothing_ratherThanAGuess() throws Exception {
        int port = startChrootedServer();

        assertThat(adapter.firstDirectory(target(port), List.of("/nowhere/at/all", "/at/all"))).isEmpty();
    }

    @Test
    void firstDirectory_ignoresAFileOfTheRightName_becauseAHomeIsADirectory() throws Exception {
        int port = startChrootedServer();
        Files.writeString(remoteRoot.resolve("geir"), "not a home");

        assertThat(adapter.firstDirectory(target(port), List.of("/geir"))).isEmpty();
    }

    @Test
    void listingTwice_leavesNoSessionBehind_soConnectionsDoNotLeak() throws Exception {
        int port = startServer();
        Files.writeString(remoteRoot.resolve("a.txt"), "a");

        adapter.list(target(port), remote(""));
        adapter.list(target(port), remote(""));

        // Every SFTP client, session and SshClient the adapter opened must be closed again — a browse that
        // leaks a session would pin an SSH connection per directory the operator clicks.
        assertThat(server.getActiveSessions()).isEmpty();
    }

    // --- slice 2: the byte-moving primitives (stat / download / mkdirs / copyFile) ---------------------

    @Test
    void stat_reportsWhetherAPathIsADirectory_andItsSize() throws Exception {
        int port = startServer();
        Files.writeString(remoteRoot.resolve("notes.txt"), "hello sftp");
        Files.createDirectory(remoteRoot.resolve("media"));

        ForBrowsingRemoteFiles.RemoteStat file = adapter.stat(target(port), remote("notes.txt"));
        assertThat(file.directory()).isFalse();
        assertThat(file.sizeBytes()).isEqualTo("hello sftp".getBytes(StandardCharsets.UTF_8).length);

        ForBrowsingRemoteFiles.RemoteStat dir = adapter.stat(target(port), remote("media"));
        assertThat(dir.directory()).isTrue();
    }

    @Test
    void stat_ofAPathThatIsNotThere_throwsNotFound() throws Exception {
        int port = startServer();

        assertThatThrownBy(() -> adapter.stat(target(port), remote("ghost")))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    void download_streamsAFilesBytesIntoTheOutputStream() throws Exception {
        int port = startServer();
        byte[] payload = "the quick brown fox".repeat(500).getBytes(StandardCharsets.UTF_8);
        Files.write(remoteRoot.resolve("big.bin"), payload);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        adapter.download(target(port), remote("big.bin"), out);

        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void mkdirs_createsANestedTree_andIsIdempotent() throws Exception {
        int port = startServer();

        adapter.mkdirs(target(port), remote("a/b/c"));
        adapter.mkdirs(target(port), remote("a/b/c")); // again — must not throw

        assertThat(Files.isDirectory(remoteRoot.resolve("a/b/c"))).isTrue();
    }

    @Test
    void copyFile_pipesBytesFromSourceToDestination_reportingProgress_andReturningTheTotal() throws Exception {
        // Two independent servers stand in for two machines; Vaier's JVM relays between them.
        int srcPort = startServer();
        SshServer destServer = SshServer.setUpDefaultServer();
        Path destRoot = Files.createTempDirectory("sftp-dest");
        try {
            destServer.setPort(0);
            destServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
            destServer.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
            destServer.setPasswordAuthenticator((u, p, s) -> "test".equals(u) && "secret".equals(p));
            destServer.start();

            byte[] payload = "relayed-through-vaier\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
            Files.write(remoteRoot.resolve("src.bin"), payload);

            java.util.concurrent.atomic.AtomicLong lastReported = new java.util.concurrent.atomic.AtomicLong();
            long total = adapter.copyFile(
                target(srcPort), remote("src.bin"),
                target(destServer.getPort()), destRoot.resolve("dst.bin").toAbsolutePath().toString(),
                lastReported::set);

            assertThat(total).isEqualTo(payload.length);
            assertThat(lastReported.get()).isEqualTo(payload.length);
            assertThat(Files.readAllBytes(destRoot.resolve("dst.bin"))).isEqualTo(payload);
        } finally {
            destServer.stop(true);
        }
    }

    @Test
    void copyFile_leavesNoSessionBehindOnEitherEnd() throws Exception {
        int srcPort = startServer();
        Files.writeString(remoteRoot.resolve("s.txt"), "s");

        adapter.copyFile(target(srcPort), remote("s.txt"),
            target(srcPort), remote("d.txt"), b -> { });

        // Both the source read-stream and the destination write-stream, and both their sessions, are closed.
        assertThat(server.getActiveSessions()).isEmpty();
    }

    // --- slice 5: deleting a file or a directory tree --------------------------------------------------

    @Test
    void delete_removesAFile() throws Exception {
        int port = startServer();
        Files.writeString(remoteRoot.resolve("notes.txt"), "bye");

        adapter.delete(target(port), remote("notes.txt"));

        assertThat(Files.exists(remoteRoot.resolve("notes.txt"))).isFalse();
    }

    @Test
    void delete_removesADirectoryTree_recursively_emptyingItDepthFirstThenRemovingIt() throws Exception {
        // The mechanic being proven: a directory cannot be rmdir'd until it is empty, so the walk must go to
        // the bottom of every branch, remove the files, remove the now-empty subdirectories, and only then
        // remove the directory itself. Files and nested directories alike must be gone.
        int port = startServer();
        Files.createDirectories(remoteRoot.resolve("proj/src/main"));
        Files.writeString(remoteRoot.resolve("proj/README.md"), "r");
        Files.writeString(remoteRoot.resolve("proj/src/main/App.java"), "class App {}");
        Files.writeString(remoteRoot.resolve("proj/src/notes.txt"), "n");

        adapter.delete(target(port), remote("proj"));

        assertThat(Files.exists(remoteRoot.resolve("proj"))).isFalse();
    }

    @Test
    void delete_ofAnEmptyDirectory_removesIt() throws Exception {
        int port = startServer();
        Files.createDirectory(remoteRoot.resolve("empty"));

        adapter.delete(target(port), remote("empty"));

        assertThat(Files.exists(remoteRoot.resolve("empty"))).isFalse();
    }

    @Test
    void delete_ofAPathThatIsNotThere_throwsNotFound_carryingThePath() throws Exception {
        int port = startServer();

        assertThatThrownBy(() -> adapter.delete(target(port), remote("ghost")))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("ghost");
    }

    @Test
    void delete_ofAPathTheSshUserCannotTouch_throwsPermissionDenied() throws Exception {
        Path locked = Files.createDirectory(remoteRoot.resolve("locked"));
        Files.writeString(locked.resolve("secret"), "s");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(locked), "running as root — cannot make a directory unreadable");
        int port = startServer();

        try {
            assertThatThrownBy(() -> adapter.delete(target(port), remote("locked")))
                .isInstanceOf(PermissionDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // --- #321: walking a tree for a zip download, over ONE connection ----------------------------------

    /** A visitor that records what the walk handed it: file relative-path -> content, and empty-dir paths. */
    private static final class RecordingVisitor implements ForBrowsingRemoteFiles.RemoteTreeVisitor {
        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> directories = new ArrayList<>();

        @Override public void directory(String relativePath) {
            directories.add(relativePath);
        }

        @Override public void file(String relativePath, InputStream content) throws IOException {
            files.put(relativePath, new String(content.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void walkTree_visitsEveryFile_andEachEmptyDirectory_namedRelativeToTheRoot() throws Exception {
        int port = startServer();
        Files.createDirectories(remoteRoot.resolve("proj/src"));
        Files.createDirectory(remoteRoot.resolve("proj/empty"));
        Files.writeString(remoteRoot.resolve("proj/README.md"), "readme");
        Files.writeString(remoteRoot.resolve("proj/src/App.java"), "class App {}");

        RecordingVisitor visitor = new RecordingVisitor();
        adapter.walkTree(target(port), remote("proj"), visitor);

        // Files carry their path relative to the walk's root; a non-empty directory (src) is implied by the
        // files inside it, while an empty directory (empty) is reported in its own right.
        assertThat(visitor.files).containsOnly(
            entry("README.md", "readme"),
            entry("src/App.java", "class App {}"));
        assertThat(visitor.directories).containsExactly("empty");
    }

    @Test
    void walkTree_skipsAFileItCannotRead_ratherThanAbortingTheWholeWalk() throws Exception {
        Path proj = Files.createDirectory(remoteRoot.resolve("proj"));
        Files.writeString(proj.resolve("readable.txt"), "ok");
        Path secret = Files.writeString(proj.resolve("secret.txt"), "shh");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(secret), "running as root — cannot make a file unreadable");
        int port = startServer();

        RecordingVisitor visitor = new RecordingVisitor();
        try {
            adapter.walkTree(target(port), remote("proj"), visitor);
        } finally {
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        }

        // The unreadable file is left out entirely — its stream never opens, so the visitor's entry for it is
        // never even started — while the walk completes and the readable file streams through.
        assertThat(visitor.files).containsOnly(entry("readable.txt", "ok"));
    }

    @Test
    void walkTree_runsOverOneConnection_neverReconnectingPerFile_andLeavesNoSessionBehind() throws Exception {
        // The bug #321 fixed: the zip walk opened a fresh SSH connection PER FILE, so a folder of dozens of
        // files became dozens of connect/authenticate/teardown cycles over the VPN — the download blew past
        // the request's async timeout and was cut off mid-stream into a corrupt zip. The whole walk, every
        // directory read and every file read, must run over a SINGLE connection, exactly as delete does.
        int port = startServer();
        Files.createDirectories(remoteRoot.resolve("many/deep"));
        for (int i = 0; i < 20; i++) {
            Files.writeString(remoteRoot.resolve("many/f" + i + ".txt"), "f" + i);
            Files.writeString(remoteRoot.resolve("many/deep/g" + i + ".txt"), "g" + i);
        }
        AtomicInteger sessionsCreated = new AtomicInteger();
        server.addSessionListener(new org.apache.sshd.common.session.SessionListener() {
            @Override public void sessionCreated(org.apache.sshd.common.session.Session session) {
                sessionsCreated.incrementAndGet();
            }
        });

        RecordingVisitor visitor = new RecordingVisitor();
        adapter.walkTree(target(port), remote("many"), visitor);

        // Every file was seen (40 across two directories) over exactly one SSH session — not one per file.
        assertThat(visitor.files).hasSize(40);
        assertThat(sessionsCreated.get()).isEqualTo(1);
        assertServerHasNoActiveSessions();
    }

    @Test
    void delete_ofATree_runsOverOneConnection_andLeavesNoSessionBehind() throws Exception {
        // The whole recursive delete must hold a single SFTP connection — a deep tree behind a VPN that
        // reconnected per entry would be pathological. When it returns, that one session is closed too.
        int port = startServer();
        Files.createDirectories(remoteRoot.resolve("a/b/c"));
        Files.writeString(remoteRoot.resolve("a/b/c/x.txt"), "x");
        Files.writeString(remoteRoot.resolve("a/y.txt"), "y");

        adapter.delete(target(port), remote("a"));

        assertServerHasNoActiveSessions();
    }
}
