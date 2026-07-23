package net.vaier.domain;

import java.util.List;
import java.util.Optional;

/**
 * What survives Vaier: every key to every backup, and the commands that use them.
 *
 * <p><b>Why this has to exist.</b> Everything needed to read Vaier's backups is currently inside Vaier. The
 * repository passphrases are encrypted in its config store; the key that decrypts them sits in the same
 * directory; and that directory is itself backed up to the backup server — encrypted with a passphrase held
 * in the store being backed up. Losing the Vaier server therefore leaves an encrypted repository whose
 * passphrase is inside itself, and every other machine's archives in the same position. Nothing warns about
 * it, because nothing is broken until the day it all is.
 *
 * <p><b>Why plain text.</b> This is read by a person who has just lost their fleet, piping {@link SurvivalKit}
 * through {@code openssl} into a terminal. A rendered page would ask them for a browser they may not have,
 * and would survive on disk after they closed it.
 *
 * <p><b>Its safety is not its own.</b> These contents are in the clear by design — they have to work for
 * someone with a laptop, no fleet and no Vaier, so they cannot be locked behind anything that was lost with
 * it. What protects them is the {@link SurvivalKit} envelope around them, and the passphrase that is only in
 * the operator's head.
 *
 * <p>Rendered here rather than in a controller for the same reason the borg setup scripts are: what this must
 * contain to be sufficient is a domain rule, and getting it wrong is only discovered on the worst day.
 */
public final class RecoverySheet {

    private RecoverySheet() {}

    /**
     * Render the contents. {@code jobs} only supplies the human name of the machine each repository holds — a
     * repository no job claims is still listed, because it still holds archives, and omitting it would mean
     * the contents quietly leave out data that exists.
     */
    public static String render(BackupServer server, List<BackupRepository> repositories,
                                List<BackupJob> jobs, String configKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("VAIER — HOW TO READ THIS FLEET'S BACKUPS\n");
        sb.append("========================================\n\n");
        sb.append("You are holding every passphrase to every backup, in the clear. That is what makes this\n");
        sb.append("work on a day when there is no Vaier left to ask. If you redirected this to a file,\n");
        sb.append("delete that file when you are done.\n\n");

        if (server == null) {
            sb.append("There is NO BACKUP SERVER in this fleet, so nothing is being backed up and there is\n");
            sb.append("nothing here to recover.\n");
            return sb.toString();
        }

        sb.append("WHERE THE BACKUPS ARE\n");
        sb.append("---------------------\n");
        row(sb, "Machine", server.machineName());
        row(sb, "Reached at", server.host() + ":" + server.sshPort());
        row(sb, "Borg user", server.borgUser());
        sb.append("\n");

        sb.append("REPOSITORIES\n");
        sb.append("------------\n");
        if (repositories == null || repositories.isEmpty()) {
            sb.append("None yet — nothing has been backed up.\n");
        } else {
            for (BackupRepository repo : repositories) {
                row(sb, "Backups of", machineFor(repo, jobs).orElse("no machine (kept, not added to)"));
                row(sb, "Repository", repo.borgRepoUrl(server));
                // A blank here would read as "no passphrase needed" and send someone away from the one
                // repository that actually needs attention.
                row(sb, "Passphrase", repo.passphrase() == null || repo.passphrase().isBlank()
                    ? "NOT STORED — Vaier does not hold this one; find it yourself before you need it"
                    : repo.passphrase());
                sb.append("\n");
            }
        }

        sb.append("READING AN ARCHIVE WITH NO VAIER\n");
        sb.append("--------------------------------\n");
        sb.append("On any machine with borg installed and network to the server above:\n\n");
        sb.append("  export BORG_PASSPHRASE='the passphrase above'\n");
        sb.append("  borg list    'the repository above'\n");
        sb.append("  borg extract 'the repository above'::ARCHIVE-NAME\n\n");
        sb.append("It extracts into the current directory. Nothing else is needed — not this fleet, not the\n");
        sb.append("Vaier server, not the key below.\n\n");

        sb.append("VAIER'S OWN CONFIGURATION KEY\n");
        sb.append("-----------------------------\n");
        sb.append("The archives above open with their passphrases alone. This key is for the step after:\n");
        sb.append("restoring Vaier itself from one of them yields a configuration whose stored credentials,\n");
        sb.append("AWS secret and passphrases are all ciphertext, and this is what decrypts them.\n\n");
        row(sb, "Config key", configKey == null || configKey.isBlank() ? "NOT AVAILABLE" : configKey);

        return sb.toString();
    }

    /** The human name of the machine a repository holds the backups of, via the job that targets it. */
    private static Optional<String> machineFor(BackupRepository repo, List<BackupJob> jobs) {
        if (jobs == null) {
            return Optional.empty();
        }
        return jobs.stream()
            .filter(j -> repo.name().equals(j.repositoryName()))
            .map(BackupJob::machineName)
            .findFirst();
    }

    /** One aligned label/value line. Values are never wrapped — a broken passphrase is a useless passphrase. */
    private static void row(StringBuilder sb, String label, String value) {
        sb.append("  ").append(pad(label)).append("  ").append(value == null ? "" : value).append("\n");
    }

    private static String pad(String label) {
        StringBuilder padded = new StringBuilder(label);
        while (padded.length() < 12) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
