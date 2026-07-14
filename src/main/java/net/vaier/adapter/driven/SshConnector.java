package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostKeyMismatchException;
import net.vaier.domain.HostKeyTrust;
import net.vaier.domain.SshAuthException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshTarget;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.IOException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one copy of Vaier's SSH connect + host-key + authenticate logic, shared by every adapter that
 * reaches a machine over SSH: the web terminal's PTY shell and exec channels
 * ({@link MinaSshSessionAdapter}) and the Explorer's SFTP client ({@link MinaSftpAdapter}).
 *
 * <p>It matters that there is only one: this is where trust-on-first-use is enforced. A second copy
 * would be a second place a host key could be accepted, and the two could drift apart. Each
 * {@link #establish} starts its own short-lived {@link SshClient} so per-connection host-key
 * verification and lifecycle stay isolated; the caller owns the returned {@link Connection} and must
 * close it.
 */
final class SshConnector {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(12);

    private SshConnector() {
    }

    /**
     * Start a client, install the host-key TOFU verifier, connect and authenticate. On any connect/auth
     * failure the client is already stopped and the matching domain SSH exception thrown.
     */
    static Connection establish(SshTarget target) {
        SshClient client = SshClient.setUpDefaultClient();
        String[] presented = new String[1];
        AtomicBoolean mismatch = new AtomicBoolean(false);
        // Host-key TOFU: the domain decides trust; the verifier only records the presented fingerprint
        // and rejects a mismatch (which aborts the connect handshake).
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
            presented[0] = fingerprint;
            HostKeyTrust trust = HostKeyTrust.evaluate(target.pinnedFingerprint(), fingerprint);
            if (trust == HostKeyTrust.MISMATCH) {
                mismatch.set(true);
                return false;
            }
            return true;
        });
        client.start();

        ClientSession session = connect(client, target, presented, mismatch);
        authenticate(client, session, target, presented, mismatch);
        return new Connection(client, session, presented[0]);
    }

    /** A started client with an authenticated session and the host-key fingerprint it presented. */
    record Connection(SshClient client, ClientSession session, String fingerprint) implements AutoCloseable {
        @Override
        public void close() {
            closeQuietly(session, client);
        }
    }

    private static ClientSession connect(SshClient client, SshTarget target,
                                         String[] presented, AtomicBoolean mismatch) {
        try {
            return client.connect(target.username(), target.host(), target.port())
                .verify(CONNECT_TIMEOUT).getSession();
        } catch (IOException e) {
            client.stop();
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw new SshConnectException(
                "Could not reach " + target.host() + ":" + target.port() + " (" + rootMessage(e) + ")", e);
        }
    }

    private static void authenticate(SshClient client, ClientSession session, SshTarget target,
                                     String[] presented, AtomicBoolean mismatch) {
        try {
            if (target.authMethod() == AuthMethod.PASSWORD) {
                session.addPasswordIdentity(target.secret());
            } else {
                for (KeyPair keyPair : loadKeyPairs(target)) {
                    session.addPublicKeyIdentity(keyPair);
                }
            }
            AuthFuture auth = session.auth().verify(AUTH_TIMEOUT);
            if (!auth.isSuccess()) {
                throw new SshAuthException("Authentication failed for " + target.username() + "@" + target.host());
            }
        } catch (SshAuthException e) {
            closeQuietly(session, client);
            // A rejected host key can surface here (MINA validates the server key as auth proceeds)
            // rather than during connect — a mismatch must still read as a host-key error, not auth.
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw e;
        } catch (Exception e) {
            closeQuietly(session, client);
            if (mismatch.get()) {
                throw new HostKeyMismatchException(target.host(), target.pinnedFingerprint(), presented[0]);
            }
            throw new SshAuthException(
                "Authentication failed for " + target.username() + "@" + target.host() + " (" + rootMessage(e) + ")", e);
        }
    }

    private static Collection<KeyPair> loadKeyPairs(SshTarget target) {
        FilePasswordProvider passwordProvider = (target.passphrase() == null || target.passphrase().isBlank())
            ? null : FilePasswordProvider.of(target.passphrase());
        try {
            Collection<KeyPair> keys = SecurityUtils.getKeyPairResourceParser()
                .loadKeyPairs(null, NamedResource.ofName("credential"), passwordProvider, target.secret());
            if (keys == null || keys.isEmpty()) {
                throw new SshAuthException("The stored private key could not be parsed");
            }
            return keys;
        } catch (SshAuthException e) {
            throw e;
        } catch (Exception e) {
            // A bad passphrase or malformed key material — an auth-side problem, not a transport one.
            throw new SshAuthException("The stored private key could not be loaded (" + rootMessage(e) + ")", e);
        }
    }

    static void closeQuietly(ClientSession session, SshClient client) {
        try {
            if (session != null) session.close(true);
        } catch (Exception ignored) {
            // best effort
        }
        try {
            client.stop();
        } catch (Exception ignored) {
            // best effort
        }
    }

    static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }
}
