package net.vaier.domain.port;

import net.vaier.domain.FileEntry;
import net.vaier.domain.SshTarget;

import java.util.List;

/**
 * Driven port for reading a machine's filesystem over SFTP — the Explorer's window onto the fleet.
 * Vaier is the only node with SSH to every machine, so it is the only place a fleet-wide file tree can
 * be assembled.
 *
 * <p>The adapter connects, authenticates and enforces host-key trust from the {@link SshTarget} exactly
 * as the terminal's SSH paths do; failures surface as the same domain SSH exceptions
 * ({@code SshConnectException}, {@code SshAuthException}, {@code HostKeyMismatchException}). Like a
 * command run, a listing reports the host key the machine presented so the caller can pin it on first
 * use.
 */
public interface ForBrowsingRemoteFiles {

    /** The entries directly inside the directory at {@code path} on {@code target}. */
    DirectoryListing list(SshTarget target, String path);

    /**
     * What one directory holds, plus the host-key fingerprint the machine presented while reading it.
     * The entries are as the remote reported them — unordered; listing order is a domain decision
     * ({@link FileEntry#listing}).
     */
    record DirectoryListing(List<FileEntry> entries, String hostKeyFingerprint) {
    }
}
