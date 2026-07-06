package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinaSshSessionAdapterTest {

    private final MinaSshSessionAdapter adapter = new MinaSshSessionAdapter();
    private SshServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop(true);
    }

    private int startServer(ProcessShellFactory shell) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setShellFactory(shell);
        server.setPasswordAuthenticator((u, p, s) -> "test".equals(u) && "secret".equals(p));
        server.start();
        return server.getPort();
    }

    private static ProcessShellFactory echoOnce(String text) {
        return new ProcessShellFactory("echo", List.of("/bin/sh", "-c", "echo " + text));
    }

    private static ProcessShellFactory cat() {
        return new ProcessShellFactory("cat", List.of("/bin/cat"));
    }

    /** Collects remote output and signals when the session closes. */
    private static final class Collector implements SshOutputListener {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final AtomicBoolean closed = new AtomicBoolean(false);

        @Override public synchronized void onOutput(byte[] data) {
            out.writeBytes(data);
        }

        @Override public void onClosed() {
            closed.set(true);
        }

        synchronized String text() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
    }

    @Test
    void passwordAuth_streamsShellOutput() throws Exception {
        int port = startServer(echoOnce("hello-terminal"));
        Collector collector = new Collector();
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        SshSession session = adapter.open(target, collector);
        await(() -> collector.text().contains("hello-terminal"));
        session.close();

        assertThat(collector.text()).contains("hello-terminal");
    }

    @Test
    void firstConnect_exposesSha256Fingerprint() throws Exception {
        int port = startServer(echoOnce("x"));
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        SshSession session = adapter.open(target, new Collector());

        assertThat(session.hostKeyFingerprint()).startsWith("SHA256:");
        session.close();
    }

    @Test
    void write_isDeliveredToRemote_andEchoedBack() throws Exception {
        int port = startServer(cat());
        Collector collector = new Collector();
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        SshSession session = adapter.open(target, collector);
        session.write("ping-123\n".getBytes(StandardCharsets.UTF_8));
        await(() -> collector.text().contains("ping-123"));
        session.close();

        assertThat(collector.text()).contains("ping-123");
    }

    @Test
    void wrongPassword_throwsSshAuthException() throws Exception {
        int port = startServer(echoOnce("x"));
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "WRONG", null, null);

        assertThatThrownBy(() -> adapter.open(target, new Collector()))
            .isInstanceOf(SshAuthException.class);
    }

    @Test
    void privateKeyAuth_authenticates() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair clientKey = gen.generateKeyPair();

        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setShellFactory(echoOnce("key-ok"));
        server.setPublickeyAuthenticator((u, key, s) -> key.equals(clientKey.getPublic()));
        server.start();
        int port = server.getPort();

        ByteArrayOutputStream pem = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "test", null, pem);
        String privateKeyPem = pem.toString(StandardCharsets.UTF_8);

        Collector collector = new Collector();
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PRIVATE_KEY,
            privateKeyPem, null, null);

        SshSession session = adapter.open(target, collector);
        await(() -> collector.text().contains("key-ok"));
        session.close();

        assertThat(collector.text()).contains("key-ok");
    }

    @Test
    void twoConcurrentSessions_areIsolated_andClosingOneDoesNotAffectTheOther() throws Exception {
        int port = startServer(cat());
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null);

        Collector collectorA = new Collector();
        Collector collectorB = new Collector();
        SshSession a = adapter.open(target, collectorA);
        SshSession b = adapter.open(target, collectorB);

        a.write("aaa-session\n".getBytes(StandardCharsets.UTF_8));
        b.write("bbb-session\n".getBytes(StandardCharsets.UTF_8));
        await(() -> collectorA.text().contains("aaa-session") && collectorB.text().contains("bbb-session"));

        // Each session sees only its own echo — no crosstalk.
        assertThat(collectorA.text()).contains("aaa-session").doesNotContain("bbb-session");
        assertThat(collectorB.text()).contains("bbb-session").doesNotContain("aaa-session");

        // Closing A must not disturb B.
        a.close();
        b.write("still-alive\n".getBytes(StandardCharsets.UTF_8));
        await(() -> collectorB.text().contains("still-alive"));
        assertThat(collectorB.text()).contains("still-alive");
        b.close();
    }

    @Test
    void pinnedFingerprintMismatch_throwsHostKeyMismatchException() throws Exception {
        int port = startServer(echoOnce("x"));
        SshTarget target = new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null,
            "SHA256:this-is-not-the-servers-key");

        assertThatThrownBy(() -> adapter.open(target, new Collector()))
            .isInstanceOf(HostKeyMismatchException.class);
    }

    @Test
    void pinnedFingerprintMatch_connects() throws Exception {
        int port = startServer(echoOnce("matched"));
        // First connect with no pin to learn the fingerprint, then reconnect pinning it.
        SshSession first = adapter.open(
            new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, null),
            new Collector());
        String fingerprint = first.hostKeyFingerprint();
        first.close();

        Collector collector = new Collector();
        SshSession session = adapter.open(
            new SshTarget("127.0.0.1", port, "test", AuthMethod.PASSWORD, "secret", null, fingerprint),
            collector);
        await(() -> collector.text().contains("matched"));
        session.close();

        assertThat(collector.text()).contains("matched");
    }
}
