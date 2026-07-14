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
