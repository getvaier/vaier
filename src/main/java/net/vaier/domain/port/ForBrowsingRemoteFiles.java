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
     * The SSH user's home <em>as this SFTP subsystem sees it</em> — the absolute path it canonicalises
     * {@code .} to. One half of the pair of answers {@link net.vaier.domain.SftpRoot} is resolved from: an
     * SFTP subsystem chrooted into {@code /volume1} answers {@code /homes/geir} where the exec channel, on
     * the same machine, answers {@code /volume1/homes/geir}. The difference is the jail.
     *
     * <p>Only the SFTP channel can answer this — that is the whole point of asking it. The exec channel's
     * half comes from {@link net.vaier.domain.port.ForRunningSshCommands}, not from here.
     */
    String home(SshTarget target);

    /**
     * The first of {@code paths} this SFTP subsystem can see as a directory, or empty when it can see none of
     * them — asked over a single connection, in the order given.
     *
     * <p>The fallback for a machine whose SFTP subsystem will not say where it is. {@link #home} is the direct
     * question, and on a chrooted machine it answers {@code /} — the jail root itself, which says nothing. So
     * the SSH user's home is <em>located</em> instead: the exec channel says where it physically is, and this
     * asks the jailed half which of that path's names it knows it by.
     *
     * <p><b>The order is the domain's</b> ({@link net.vaier.domain.SftpRoot#jailCandidates}), and so is the
     * meaning of a hit. The adapter probes and reports; it does not choose the candidates and does not decide
     * what a match implies.
     */
    java.util.Optional<String> firstDirectory(SshTarget target, List<String> paths);

    /**
     * Whether the file at {@code path} on {@code target} is a directory, and how many bytes it is — the
     * one question a transfer or a download asks before it moves anything (a directory is walked, a file is
     * streamed; the size is a download's {@code Content-Length} and a transfer's progress denominator).
     * Fails with the same domain SSH exceptions as {@link #list}.
     */
    RemoteStat stat(SshTarget target, String path);

    /**
     * Stream the file at {@code path} on {@code target} into {@code out} — the byte source for an HTTP
     * download, piped with a fixed buffer so memory stays flat regardless of file size. The caller owns
     * {@code out}; this owns the SFTP session and closes it. Fails with the same domain SSH exceptions as
     * {@link #list}.
     */
    void download(SshTarget target, String path, java.io.OutputStream out);

    /**
     * Create the directory at {@code path} on {@code target}, and every parent it needs — idempotent, so an
     * already-present directory is fine. The dest side of a transfer calls this to lay down the tree before
     * copying files into it. Fails with the same domain SSH exceptions as {@link #list}.
     */
    void mkdirs(SshTarget target, String path);

    /**
     * The flat-memory relay: open an SFTP read stream on {@code srcTarget}/{@code srcPath} and an SFTP write
     * stream on {@code destTarget}/{@code destPath} and pipe one into the other with a fixed buffer, calling
     * {@code onBytes} with the cumulative byte count as it goes, and returning the total copied. Both
     * sessions are held open concurrently for the duration and both are closed before returning — Vaier's
     * JVM is the only relay point, so neither machine ever needs SSH to the other. Fails with the same domain
     * SSH exceptions as {@link #list}.
     */
    long copyFile(SshTarget srcTarget, String srcPath, SshTarget destTarget, String destPath,
                  java.util.function.LongConsumer onBytes);

    /**
     * What one directory holds, plus the host-key fingerprint the machine presented while reading it.
     * The entries are as the remote reported them — unordered; listing order is a domain decision
     * ({@link FileEntry#listing}).
     */
    record DirectoryListing(List<FileEntry> entries, String hostKeyFingerprint) {
    }

    /** Whether a path is a directory, and its size in bytes — the answer to {@link #stat}. */
    record RemoteStat(boolean directory, long sizeBytes) {
    }
}
