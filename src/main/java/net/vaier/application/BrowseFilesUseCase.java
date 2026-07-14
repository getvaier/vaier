package net.vaier.application;

import net.vaier.domain.FileEntry;
import net.vaier.domain.SftpRoot;

import java.util.List;

/**
 * Browse a machine's filesystem — the Explorer's read side. Lists what one directory on one machine
 * holds, so the operator can walk the fleet's files from a single tree.
 */
public interface BrowseFilesUseCase {

    /**
     * What the directory at {@code path} on {@code machineName} holds, in listing order (directories
     * before files, then by name) — at the machine's <b>true</b> coordinates, the ones {@code df}, borg and
     * the operator's own terminal use.
     *
     * @param path the directory to list, as the machine itself names it, or {@code null} to begin at the
     *             machine's {@link SftpRoot} — which the browser cannot know until it has asked
     * @throws IllegalArgumentException when {@code path} is not a browsable absolute path
     * @throws net.vaier.domain.PathOutsideSftpRootException when {@code path} is above the machine's SFTP
     *         root, and so unreachable over SFTP however valid a path it is
     * @throws net.vaier.domain.NotFoundException when no machine bears the name
     * @throws net.vaier.domain.NoHostCredentialException when the machine has no credential in the vault
     */
    MachineDirectory listDirectory(String machineName, String path);

    /**
     * One directory on one machine: the {@code entries} it holds, the {@code path} they were read from, and
     * the {@link SftpRoot} this machine's file tree begins at.
     *
     * <p>The root travels with every listing because the browser cannot deduce it. A machine's tree does not
     * necessarily begin at {@code /} — it begins wherever its SFTP subsystem is rooted, and everything above
     * that is unreachable over SFTP. A caller that assumed {@code /} would open the NAS on the one path the
     * NAS cannot answer.
     */
    record MachineDirectory(SftpRoot root, String path, List<FileEntry> entries) {
    }
}
