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

    /**
     * The identifier charset a repository {@code name} is confined to. A name is used verbatim as a
     * shell/path token in every borg command (the derived repo path, the pass-file name, the
     * {@code --restrict-to-path} confinement), so anything outside {@code [A-Za-z0-9_-]} — a space, a
     * shell metacharacter, a path separator — is rejected at construction. Mirrors {@link PeerId}'s rule
     * for exactly the same reason; deliberately not shared with it, since a VPN peer and a backup
     * repository are unrelated concepts.
     */
    private static final String NAME_PATTERN = "[A-Za-z0-9_-]+";

    /**
     * The safe charset for an operator-settable <em>path</em> field (the {@link #repoPath} override): it
     * may legitimately contain {@code /} and {@code .}, so it is not an identifier, but it must still be
     * free of spaces and shell metacharacters ({@code ; $ " ' `} etc.) that could break out of quoting.
     */
    static final String PATH_PATTERN = "[A-Za-z0-9._/-]+";

    public BackupRepository {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Backup repository name must not be blank");
        }
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                "Backup repository name must contain only [A-Za-z0-9_-]: " + name);
        }
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("Backup repository serverName must not be blank");
        }
        // repoPath is a nullable override: null/blank means "derive from the server". When present it is a
        // real path (may hold '/' and '.'), so it is validated against the safe-path charset, not rejected.
        if (repoPath != null && !repoPath.isBlank() && !repoPath.matches(PATH_PATTERN)) {
            throw new IllegalArgumentException(
                "Backup repository repoPath must contain only [A-Za-z0-9._/-]: " + repoPath);
        }
    }

    /**
     * Slugs raw operator input into a valid repository {@code name}: any character outside
     * {@code [A-Za-z0-9_-]} becomes a hyphen, runs of hyphens collapse to one, and leading/trailing
     * hyphens are dropped ({@code "NUC 02"} → {@code "NUC-02"}). Case is preserved. This is the helper the
     * UI mirrors client-side so an operator can still type a friendly name. Self-contained — it replicates
     * {@link PeerId#sanitized}'s rule locally rather than sharing it, since the two concepts are unrelated.
     *
     * @throws IllegalArgumentException if the input is null or slugs to nothing
     */
    public static String sanitizedName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Backup repository name is required");
        }
        String cleaned = raw.trim()
            .replaceAll("[^A-Za-z0-9_-]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("^-|-$", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Backup repository name is empty after sanitisation");
        }
        return cleaned;
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
