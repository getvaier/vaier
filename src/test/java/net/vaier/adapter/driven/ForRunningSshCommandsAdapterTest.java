package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.SshTarget;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exec-channel contract for {@link MinaSshSessionAdapter} as a {@code ForRunningSshCommands} adapter,
 * exercised against an embedded MINA {@link SshServer} that actually executes the exec command line.
 */
class ForRunningSshCommandsAdapterTest {

    private final MinaSshSessionAdapter adapter =
        new MinaSshSessionAdapter(Duration.ofSeconds(20), 1024 * 1024);
    private SshServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(true);
    }

    private int startServer() throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setCommandFactory(ProcessShellCommandFactory.INSTANCE);
        server.setPasswordAuthenticator((u, p, s) -> "test".equals(u) && "secret".equals(p));
        server.start();
        return server.getPort();
    }

    private SshTarget target(int port, String pinnedFingerprint) {
        return new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, pinnedFingerprint);
    }

    @Test
    void run_capturesStdout_andZeroExit() throws Exception {
        int port = startServer();

        CommandResult result = adapter.run(target(port, null), "echo hello");

        assertThat(result.stdout()).contains("hello");
        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void run_surfacesNonZeroExit_withoutThrowing() throws Exception {
        int port = startServer();

        CommandResult result = adapter.run(target(port, null), "/bin/sh -c \"exit 3\"");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void run_capturesStderr() throws Exception {
        int port = startServer();

        CommandResult result = adapter.run(target(port, null), "/bin/sh -c \"echo oops 1>&2\"");

        assertThat(result.stderr()).contains("oops");
    }

    @Test
    void run_thatExceedsTimeout_returnsTimedOut_andDoesNotHang() throws Exception {
        int port = startServer();
        MinaSshSessionAdapter shortTimeout =
            new MinaSshSessionAdapter(Duration.ofMillis(500), 1024 * 1024);

        long start = System.currentTimeMillis();
        CommandResult result = shortTimeout.run(target(port, null), "sleep 5");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.timedOut()).isTrue();
        assertThat(elapsed).isLessThan(4000);
    }

    @Test
    void run_cappedOutput_doesNotExceedCap() throws Exception {
        int port = startServer();
        MinaSshSessionAdapter tinyCap =
            new MinaSshSessionAdapter(Duration.ofSeconds(20), 8);

        CommandResult result = tinyCap.run(target(port, null), "echo hello-world-this-is-a-long-line");

        assertThat(result.stdout().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8);
    }

    @Test
    void run_reportsPresentedHostKeyFingerprint_forTofuPinning() throws Exception {
        int port = startServer();

        CommandResult result = adapter.run(target(port, null), "echo hello");

        assertThat(result.hostKeyFingerprint()).isNotBlank();
        assertThat(result.hostKeyFingerprint()).startsWith("SHA256:");
    }

    @Test
    void run_pinnedFingerprintMismatch_throwsHostKeyMismatchException() throws Exception {
        int port = startServer();

        assertThatThrownBy(() ->
            adapter.run(target(port, "SHA256:this-is-not-the-servers-key"), "echo hello"))
            .isInstanceOf(HostKeyMismatchException.class);
    }
}
