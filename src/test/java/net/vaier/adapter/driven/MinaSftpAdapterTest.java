package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.FileEntry;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
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
