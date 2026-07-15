package net.vaier.domain;

/**
 * A delete asked to remove a machine's {@link SftpRoot} itself — the whole browsable file tree, where the
 * Explorer begins. Deleting a machine's entire tree is never a paste-shaped mistake to make easy, so it is
 * refused in the domain before any SFTP call is made, however the frontend gates the operation.
 *
 * <p>An {@link IllegalArgumentException}: the path is a perfectly real path, it is simply not one this
 * operation may act on — so it surfaces as a {@code 400} carrying its own sentence, which names the root the
 * operator tried to delete.
 */
public class CannotDeleteSftpRootException extends IllegalArgumentException {

    public CannotDeleteSftpRootException(String rootPath) {
        super("Refusing to delete " + rootPath + ": it is this machine's SFTP root, the whole browsable file tree.");
    }
}
