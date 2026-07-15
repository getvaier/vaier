package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.adapter.driven.SshConnector.Connection;
import net.vaier.domain.FileEntry;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache MINA SFTP adapter — the Explorer's window onto a machine's filesystem. It hangs an SFTP client
 * off the same connect + authenticate + host-key trust-on-first-use machinery the web terminal uses
 * ({@link SshConnector}), so browsing a machine trusts its host key by exactly the same rule as opening
 * a shell on it.
 *
 * <p>Each listing owns its whole lifecycle: a short-lived {@link org.apache.sshd.client.SshClient}, a
 * session and an SFTP channel, all closed again before it returns — a browse that leaked a session would
 * pin an SSH connection to a machine for every directory the operator clicked.
 *
 * <p>Translation only, no decisions: the remote's {@code readdir} answer becomes {@link FileEntry}
 * values, and the domain decides what a path is and what order a directory reads in. The one thing the
 * adapter drops is {@code .} and {@code ..} — those are artifacts of the SFTP protocol, not files.
 */
@Component
@Slf4j
public class MinaSftpAdapter implements ForBrowsingRemoteFiles {

    private static final String SELF = ".";
    private static final String PARENT = "..";

    /** The fixed relay buffer: the whole point is that memory stays flat regardless of a file's size. */
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    @Override
    public DirectoryListing list(SshTarget target, String path) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            List<FileEntry> entries = new ArrayList<>();
            for (SftpClient.DirEntry entry : sftp.readDir(path)) {
                String name = entry.getFilename();
                if (SELF.equals(name) || PARENT.equals(name)) {
                    continue;
                }
                entries.add(toFileEntry(path, name, entry.getAttributes()));
            }
            log.debug("Listed {} entries in {} on {}", entries.size(), path, target.host());
            return new DirectoryListing(List.copyOf(entries), conn.fingerprint());

        } catch (IOException | UncheckedIOException e) {
            // UncheckedIOException is not belt-and-braces: MINA's readDir is lazy, so an SFTP status arrives
            // when the iterable is walked, wrapped as unchecked. Catching only IOException would let it escape.
            throw translate(e, target, path);
        }
    }

    /**
     * What the SFTP subsystem canonicalises {@code .} to on first connect — the SSH user's home, in the
     * coordinates SFTP itself speaks. On a chrooted subsystem that is a path inside the jail, and comparing
     * it with the exec channel's {@code $HOME} is what reveals the jail ({@link net.vaier.domain.SftpRoot}).
     *
     * <p>Translation only: the adapter asks the protocol its own question and hands back the string. What the
     * answer <em>means</em> — whether this machine is jailed, and where — is the domain's to decide.
     */
    @Override
    public String home(SshTarget target) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            String home = sftp.canonicalPath(SELF);
            log.debug("SFTP on {} canonicalises \".\" to {}", target.host(), home);
            return home;

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, SELF);
        }
    }

    /**
     * Asks the SFTP subsystem, over one connection, which of {@code paths} it can see as a directory — and
     * stops at the first. Translation only: the candidates and their order are the domain's
     * ({@link net.vaier.domain.SftpRoot#jailCandidates}), and what a hit <em>means</em> is the domain's too.
     *
     * <p>One session for the whole search, not one per candidate: a machine on the far side of a VPN answers
     * a connect in the better part of a second, and a home three directories deep would otherwise cost three
     * of them. A candidate that is absent, or that the SSH user may not stat, is simply not a match — the
     * search moves on rather than failing, because a machine legitimately cannot see most of the names asked
     * about (that is the entire point of asking).
     */
    @Override
    public java.util.Optional<String> firstDirectory(SshTarget target, List<String> paths) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            for (String path : paths) {
                if (isDirectory(sftp, path)) {
                    log.debug("SFTP on {} can see {}", target.host(), path);
                    return java.util.Optional.of(path);
                }
            }
            return java.util.Optional.empty();

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, String.join(", ", paths));
        }
    }

    @Override
    public RemoteStat stat(SshTarget target, String path) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            SftpClient.Attributes attrs = sftp.stat(path);
            return new RemoteStat(attrs.isDirectory(), Math.max(0, attrs.getSize()));

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, path);
        }
    }

    @Override
    public void download(SshTarget target, String path, java.io.OutputStream out) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session());
             java.io.InputStream in = sftp.read(path)) {

            in.transferTo(out);
            log.debug("Downloaded {} from {}", path, target.host());

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, path);
        }
    }

    @Override
    public void mkdirs(SshTarget target, String path) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            mkdirsOver(sftp, path);
            log.debug("Ensured directory {} on {}", path, target.host());

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, path);
        }
    }

    @Override
    public void walkTree(SshTarget target, String rootPath, RemoteTreeVisitor visitor) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            // One establish for the whole depth-first walk — every directory read and every file read run over
            // this one open session, so a deep tree behind a VPN is never a reconnect per entry.
            walk(sftp, target, rootPath, "", visitor);
            log.debug("Walked the tree at {} on {}", rootPath, target.host());

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, rootPath);
        }
    }

    /**
     * One directory of the walk over an already-open SFTP session, entries named relative to the walk's root
     * ({@code prefix}, joined with forward slashes). A directory this SSH user cannot read, or that vanished
     * mid-walk, is an ordinary state of a fleet ({@link NotFoundException}, {@link PermissionDeniedException})
     * — that subtree is simply skipped, not allowed to fail a walk that may already have streamed other
     * entries. An empty directory (below the root — the root itself carries no prefix) is reported as itself,
     * the one way a caller can learn a folder holds nothing; a non-empty one is implied by the paths of the
     * entries inside it. The recursion never opens a second connection.
     */
    private void walk(SftpClient sftp, SshTarget target, String path, String prefix, RemoteTreeVisitor visitor) {
        List<SftpClient.DirEntry> children;
        try {
            children = childrenOf(sftp, path);
        } catch (IOException | UncheckedIOException e) {
            if (skip(translate(e, target, path), path, target)) {
                return;
            }
            throw translate(e, target, path);
        }
        if (children.isEmpty()) {
            if (!prefix.isEmpty()) {
                emitDirectory(visitor, prefix);
            }
            return;
        }
        for (SftpClient.DirEntry child : children) {
            String name = child.getFilename();
            String childPath = childPath(path, name);
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            if (child.getAttributes().isDirectory()) {
                walk(sftp, target, childPath, rel, visitor);
            } else {
                emitFile(sftp, target, childPath, rel, visitor);
            }
        }
    }

    /** The entries of {@code path}, with the protocol's own {@code .} and {@code ..} artifacts dropped. */
    private static List<SftpClient.DirEntry> childrenOf(SftpClient sftp, String path) throws IOException {
        List<SftpClient.DirEntry> children = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            String name = entry.getFilename();
            if (SELF.equals(name) || PARENT.equals(name)) {
                continue;
            }
            children.add(entry);
        }
        return children;
    }

    /**
     * Stream one file straight from the open SFTP session into the visitor. The read stream is opened
     * <em>before</em> the visitor is called — MINA opens the file eagerly, so a permission-denied or a
     * vanished file surfaces here, and the file is skipped without the visitor's entry ever being started.
     * Only once a valid stream is in hand does the visitor write it, so a skip can never leave a half-written
     * entry behind. A failure of the visitor itself (the caller writing the entry), or a read that breaks
     * mid-stream, is a real failure and aborts the walk.
     */
    private void emitFile(SftpClient sftp, SshTarget target, String path, String rel, RemoteTreeVisitor visitor) {
        java.io.InputStream in;
        try {
            in = sftp.read(path);
        } catch (IOException | UncheckedIOException e) {
            if (skip(translate(e, target, path), path, target)) {
                return;
            }
            throw translate(e, target, path);
        }
        try (java.io.InputStream stream = in) {
            visitor.file(rel, stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void emitDirectory(RemoteTreeVisitor visitor, String rel) {
        try {
            visitor.directory(rel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code translated} is an ordinary unreadable/vanished entry to skip, logging it if so. */
    private boolean skip(RuntimeException translated, String path, SshTarget target) {
        if (translated instanceof NotFoundException || translated instanceof PermissionDeniedException) {
            log.warn("Skipping {} on {} in tree walk ({})", path, target.host(), translated.getMessage());
            return true;
        }
        return false;
    }

    @Override
    public void delete(SshTarget target, String path) {
        try (Connection conn = SshConnector.establish(target);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(conn.session())) {

            // One stat for the given path; every child carries its own kind in the readdir answer, so the walk
            // costs no extra stat per entry — and the whole recursion runs over this one open session.
            removeTree(sftp, path, sftp.stat(path).isDirectory());
            log.debug("Deleted {} on {}", path, target.host());

        } catch (IOException | UncheckedIOException e) {
            throw translate(e, target, path);
        }
    }

    /**
     * Remove {@code path} over an already-open SFTP session: a file outright, a directory by emptying it
     * depth-first — its files removed, its subdirectories recursed into and removed bottom-up — and then
     * removing the now-empty directory itself, because SFTP can only {@code rmdir} an empty directory. The
     * protocol's own {@code .} and {@code ..} are skipped: they are artifacts, not entries to delete. The
     * recursion never opens a second connection, so a deep tree behind a VPN is one connection, not one per
     * entry.
     */
    private static void removeTree(SftpClient sftp, String path, boolean directory) throws IOException {
        if (directory) {
            for (SftpClient.DirEntry entry : sftp.readDir(path)) {
                String name = entry.getFilename();
                if (SELF.equals(name) || PARENT.equals(name)) {
                    continue;
                }
                removeTree(sftp, childPath(path, name), entry.getAttributes().isDirectory());
            }
            sftp.rmdir(path);
        } else {
            sftp.remove(path);
        }
    }

    /** The path of {@code name} inside the directory {@code parent} — the same join the domain's FileEntry makes. */
    private static String childPath(String parent, String name) {
        return "/".equals(parent) ? "/" + name : parent + "/" + name;
    }

    /**
     * The flat-memory relay. Both ends' sessions are held open at once — a source read stream and a dest
     * write stream — and piped with one fixed buffer, so memory stays flat however large the file is. Every
     * client, session and stream the relay opened is closed again before it returns.
     */
    @Override
    public long copyFile(SshTarget srcTarget, String srcPath, SshTarget destTarget, String destPath,
                         java.util.function.LongConsumer onBytes) {
        try (Connection srcConn = SshConnector.establish(srcTarget);
             SftpClient srcSftp = SftpClientFactory.instance().createSftpClient(srcConn.session());
             Connection destConn = SshConnector.establish(destTarget);
             SftpClient destSftp = SftpClientFactory.instance().createSftpClient(destConn.session());
             java.io.InputStream in = srcSftp.read(srcPath);
             java.io.OutputStream out = destSftp.write(destPath)) {

            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long copied = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                onBytes.accept(copied);
            }
            log.debug("Relayed {} bytes from {}:{} to {}:{}",
                copied, srcTarget.host(), srcPath, destTarget.host(), destPath);
            return copied;

        } catch (IOException | UncheckedIOException e) {
            // Either end can be the failing one; the message names the pair so a failure is diagnosable.
            throw translate(e, destTarget, srcTarget.host() + ":" + srcPath + " -> " + destPath);
        }
    }

    /**
     * Create {@code path} and every parent it needs over an open SFTP session, idempotently. SFTP has no
     * {@code mkdir -p}, so each ancestor is created in turn and an already-present one is not an error — the
     * whole point is that laying down a destination tree can be repeated safely.
     */
    private static void mkdirsOver(SftpClient sftp, String path) throws IOException {
        String normalised = FileEntry.normalisePath(path);
        if ("/".equals(normalised)) {
            return;
        }
        StringBuilder prefix = new StringBuilder();
        for (String segment : normalised.substring(1).split("/")) {
            prefix.append('/').append(segment);
            String dir = prefix.toString();
            if (!isDirectory(sftp, dir)) {
                try {
                    sftp.mkdir(dir);
                } catch (IOException e) {
                    // A racing create, or a directory that appeared between the check and the mkdir, is fine;
                    // only a still-absent directory afterwards is a real failure.
                    if (!isDirectory(sftp, dir)) {
                        throw e;
                    }
                }
            }
        }
    }

    /** Whether the SFTP subsystem sees a directory at {@code path}. Anything it cannot stat is simply not one. */
    private static boolean isDirectory(SftpClient sftp, String path) {
        try {
            return sftp.stat(path).isDirectory();
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    /**
     * The remote's answer, as the domain sees it. A directory that is not there and a directory the SSH user
     * may not read are <b>ordinary states of a fleet</b>, not faults — Vaier's SSH users are not root on most
     * machines, so much of a filesystem is legitimately unreadable. Collapsing them into a transport failure
     * would surface as a 500 and tell the operator that Vaier broke, when in truth the answer is simply "that
     * folder is gone" or "you cannot read this one". Anything else really is a transport failure.
     */
    private static RuntimeException translate(Exception e, SshTarget target, String path) {
        Integer status = sftpStatus(e);
        String where = path + " on " + target.host();
        if (status != null && status == SftpConstants.SSH_FX_NO_SUCH_FILE) {
            return new NotFoundException("No such directory: " + where);
        }
        if (status != null && status == SftpConstants.SSH_FX_PERMISSION_DENIED) {
            return new PermissionDeniedException(
                "Not allowed to read " + where + " as " + target.username() + ".");
        }
        return new SshConnectException(
            "Could not list " + where + " (" + SshConnector.rootMessage(e) + ")", e);
    }

    /** The SFTP status code buried in a failure, or {@code null} when it was not an SFTP-level error at all. */
    private static Integer sftpStatus(Throwable t) {
        for (Throwable cur = t; cur != null && cur.getCause() != cur; cur = cur.getCause()) {
            if (cur instanceof SftpException sftp) {
                return sftp.getStatus();
            }
        }
        return null;
    }

    private static FileEntry toFileEntry(String parentPath, String name, SftpClient.Attributes attrs) {
        // The name is joined to the requested directory by the domain, so a remote that answers readdir
        // with a path-shaped name cannot fabricate an entry outside the directory being listed.
        return FileEntry.in(parentPath, name, attrs.isDirectory(), Math.max(0, attrs.getSize()), modifiedAt(attrs));
    }

    /** A server that reports no mtime still lists — the entry simply carries the epoch. */
    private static Instant modifiedAt(SftpClient.Attributes attrs) {
        FileTime modifyTime = attrs.getModifyTime();
        return modifyTime != null ? modifyTime.toInstant() : Instant.EPOCH;
    }
}
