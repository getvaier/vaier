package net.vaier.domain.port;

import net.vaier.domain.FileEntry;
import net.vaier.domain.SshTarget;

import java.io.IOException;
import java.io.InputStream;
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
     * Delete the file or directory at {@code path} on {@code target}. A file is removed outright; a directory
     * is emptied depth-first (its files removed, its subdirectories recursed into and removed bottom-up) and
     * then removed itself — the whole recursive walk over a <b>single</b> SFTP connection, so a deep tree
     * behind a VPN never reconnects per entry. There is no time coordinate: only the live filesystem is ever
     * deleted (an archive is read-only). Fails with the same domain SSH exceptions as {@link #list}.
     */
    void delete(SshTarget target, String path);

    /**
     * Walk the directory tree rooted at {@code rootPath} on {@code target} depth-first over a <b>single</b>
     * SFTP connection, handing every entry to {@code visitor}: a file with a stream open only for the
     * duration of the callback ({@link RemoteTreeVisitor#file}), and an empty directory as itself
     * ({@link RemoteTreeVisitor#directory} — a non-empty directory is implied by the relative paths of the
     * files inside it and reported no other way). The paths handed to the visitor are <b>relative</b> to
     * {@code rootPath}, joined with forward slashes, so the caller can name entries without knowing where the
     * root sits on the machine.
     *
     * <p>The whole walk and every file read run over the one connection — exactly the discipline
     * {@link #delete} uses for a recursive delete — so a deep tree behind a VPN is never a fresh connect,
     * authenticate and teardown <em>per entry</em>. That per-file reconnect is what turned a directory of
     * dozens of files into dozens of round trips over the VPN, blew past the request's async timeout, and cut
     * a download off mid-stream into a corrupt archive.
     *
     * <p><b>Resilience.</b> A file or subdirectory this SSH user cannot read, or that vanishes mid-walk, is an
     * ordinary state of a fleet ({@link net.vaier.domain.NotFoundException},
     * {@link net.vaier.domain.PermissionDeniedException}) — it is <b>skipped</b>, never allowed to abort a walk
     * that may already have streamed other entries. A file's stream is opened <em>before</em> the visitor is
     * called, so a skip never opens the visitor's entry at all: nothing half-written can corrupt the archive.
     */
    void walkTree(SshTarget target, String rootPath, RemoteTreeVisitor visitor);

    /**
     * The sink for {@link #walkTree}: each entry of the tree, named relative to the walk's root. The file
     * stream is valid only for the duration of {@link #file} — read it through, do not retain it past the call.
     */
    interface RemoteTreeVisitor {

        /** An empty directory in the tree — the only directory reported, since files carry their own path. */
        void directory(String relativePath) throws IOException;

        /** A file in the tree, its bytes readable from {@code content} only until this call returns. */
        void file(String relativePath, InputStream content) throws IOException;
    }

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
