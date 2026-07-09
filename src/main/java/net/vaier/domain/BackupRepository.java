package net.vaier.domain;

/**
 * The NAS borg store a fleet backup pushes into: where it lives, how to reach its {@code borg serve}
 * over SSH, and the passphrase that unlocks it. This is the "Backup repository" of the fleet-backup
 * feature — deliberately distinct from the §6.7/#153 "Backup snapshot" (an export of Vaier's own
 * config), which shares no vocabulary with it.
 *
 * <p>The entity owns the one piece of borg knowledge that is purely about the repository's location:
 * {@link #borgRepoUrl()} renders the {@code ssh://user@host:port/path} URL borg addresses the store by.
 * The passphrase is carried so a run can export it into borg's environment; {@link #withPassphrase(String)}
 * lets an adapter attach a decrypted secret to a repository loaded without one.
 *
 * @param nasHost    the host running the borg-server ({@code borg serve}) sshd
 * @param sshPort    the port that sshd listens on (the borg container's, not the host's SSH)
 * @param borgUser   the SSH user the borg key authenticates as (typically {@value #DEFAULT_BORG_USER})
 * @param repoPath   the repository path on the server, appended after the URL host:port
 * @param passphrase the borg repository passphrase (never logged; masked when a command is rendered)
 * @param appendOnly whether the client key is restricted to append-only (documents a hardening choice;
 *                   V1 ships a delete-capable key so nightly prune/compact work)
 */
public record BackupRepository(
    String name,
    String nasHost,
    int sshPort,
    String borgUser,
    String repoPath,
    String passphrase,
    boolean appendOnly
) {

    /** The borg-server container's sshd port on the NAS (not the NAS host's own SSH). */
    public static final int DEFAULT_SSH_PORT = 8022;

    /** The conventional SSH user the borg client key authenticates as. */
    public static final String DEFAULT_BORG_USER = "borg";

    public BackupRepository {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backup repository name must not be blank");
        }
        if (nasHost == null || nasHost.isBlank()) {
            throw new IllegalArgumentException("Backup repository nasHost must not be blank");
        }
        if (repoPath == null || repoPath.isBlank()) {
            throw new IllegalArgumentException("Backup repository repoPath must not be blank");
        }
        if (sshPort < 1 || sshPort > 65535) {
            throw new IllegalArgumentException("Backup repository sshPort must be 1..65535, was " + sshPort);
        }
    }

    /** The {@code ssh://<borgUser>@<nasHost>:<sshPort>/<repoPath>} URL borg addresses this store by. */
    public String borgRepoUrl() {
        return "ssh://" + borgUser + "@" + nasHost + ":" + sshPort + "/" + repoPath;
    }

    /** A copy of this repository with {@code passphrase} replaced — used to attach a decrypted secret. */
    public BackupRepository withPassphrase(String newPassphrase) {
        return new BackupRepository(name, nasHost, sshPort, borgUser, repoPath, newPassphrase, appendOnly);
    }
}
