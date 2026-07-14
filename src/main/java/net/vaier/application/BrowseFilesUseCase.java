package net.vaier.application;

import net.vaier.domain.FileEntry;

import java.util.List;

/**
 * Browse a machine's filesystem — the Explorer's read side. Lists what one directory on one machine
 * holds, so the operator can walk the fleet's files from a single tree.
 */
public interface BrowseFilesUseCase {

    /**
     * What the directory at {@code path} on {@code machineName} holds, in listing order (directories
     * before files, then by name).
     *
     * @throws IllegalArgumentException when {@code path} is not a browsable absolute path
     * @throws net.vaier.domain.NotFoundException when no machine bears the name
     * @throws net.vaier.domain.NoHostCredentialException when the machine has no credential in the vault
     */
    List<FileEntry> listDirectory(String machineName, String path);
}
