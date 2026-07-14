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
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
