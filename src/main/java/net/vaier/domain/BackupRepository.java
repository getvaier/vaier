package net.vaier.domain;

/**
 * One borg repository living on a {@link BackupServer}: which server holds it, the (optional) path override,
 * and the passphrase that unlocks it. This is the "Backup repository" of the fleet-backup feature —
 * deliberately distinct from the §6.7/#153 "Backup snapshot" (an export of Vaier's own config), which
 * shares no vocabulary with it.
 *
 * <p>Server coordinates (host, port, borg user, base path) used to be fused onto this record; they now live
 * on {@link BackupServer}, so a repository is simply "a name on a server". The entity still owns the borg
 * knowledge about the repository's own location, but always relative to its server:
 * {@link #repoPathOn(BackupServer)} resolves the path (the explicit override, or {@code base + "/" + name}
 * derived from the server) and {@link #borgRepoUrl(BackupServer)} renders the {@code ssh://user@host:port/path}
 * URL borg addresses the store by. The passphrase is carried so a run can export it into borg's environment;
 * {@link #withPassphrase(String)} lets an adapter attach a decrypted secret to a repository loaded without one.
 *
 * @param name       the Vaier-facing identity of this repository, unique within the store
 * @param serverName the {@link BackupServer} this repository lives on (never blank)
 * @param repoPath   an explicit path override; <strong>nullable/blank</strong>, meaning "derive from the
 *                   server's {@link BackupServer#baseRepoPath()} and this repository's name"
 * @param passphrase the borg repository passphrase (never logged; masked when a command is rendered)
 * @param appendOnly whether the client key is restricted to append-only (documents a hardening choice;
 *                   V1 ships a delete-capable key so nightly prune/compact work)
 */
public record BackupRepository(
    String name,
    String serverName,
    String repoPath,
    String passphrase,
    boolean appendOnly
) {

    public BackupRepository {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backup repository name must not be blank");
        }
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("Backup repository serverName must not be blank");
        }
        // repoPath is a nullable override: null/blank means "derive from the server", so it is not validated.
    }

    /**
     * The repository path on its server: the explicit {@link #repoPath} override when set, otherwise derived
     * as {@code <server baseRepoPath>/<name>}. The derived form is why a new repository can be added by name
     * alone — the server supplies the base.
     */
    public String repoPathOn(BackupServer server) {
        return (repoPath == null || repoPath.isBlank()) ? server.baseRepoPath() + "/" + name : repoPath;
    }

    /**
     * The {@code ssh://<borgUser>@<host>:<sshPort>/<path>} URL borg addresses this store by, built on the
     * server's {@link BackupServer#sshUrlPrefix()} and {@link #repoPathOn(BackupServer)}. Because the
     * server's base path carries no leading slash and the prefix ends at the port, exactly one {@code /} is
     * inserted, yielding an absolute remote path.
     */
    public String borgRepoUrl(BackupServer server) {
        return server.sshUrlPrefix() + "/" + repoPathOn(server);
    }

    /** A copy of this repository with {@code passphrase} replaced — used to attach a decrypted secret. */
    public BackupRepository withPassphrase(String newPassphrase) {
        return new BackupRepository(name, serverName, repoPath, newPassphrase, appendOnly);
    }
}
