package net.vaier.domain;

import java.util.List;
import java.util.Optional;

/**
 * The one page that survives Vaier: every key to every backup, in the clear, on paper.
 *
 * <p><b>Why this has to exist.</b> Everything needed to read Vaier's backups is currently inside Vaier. The
 * repository passphrases are encrypted in its config store; the key that decrypts them sits in the same
 * directory; and that directory is itself backed up to the backup server — encrypted with a passphrase held
 * in the store being backed up. Losing the Vaier server therefore leaves an encrypted repository whose
 * passphrase is inside itself, and every other machine's archives in the same position. Nothing warns about
 * it, because nothing is broken until the day it all is.
 *
 * <p><b>Why it is plaintext, and why that is the point.</b> This is deliberately the most dangerous artefact
 * Vaier produces. It has to be usable by a person with a laptop, no fleet, no Vaier and possibly no network
 * beyond the backup server — so it cannot be encrypted with something they would also have lost. Its safety
 * comes from where it is kept, not from what it is, which is why the page says so on its face. A recovery
 * sheet stored on the fleet it protects is not escrow; it is a second copy of the problem.
 *
 * <p>Rendered here rather than in a controller for the same reason the borg setup scripts are: what the sheet
 * must contain to be sufficient is a domain rule, and getting it wrong is only discovered on the worst day.
 */
public final class RecoverySheet {

    private RecoverySheet() {}

    /**
     * Render the sheet. {@code jobs} only supplies the human name of the machine each repository holds — a
     * repository no job claims is still printed, because it still holds archives, and omitting it would mean
     * the sheet quietly leaves out data that exists.
     */
    public static String render(BackupServer server, List<BackupRepository> repositories,
                                List<BackupJob> jobs, String configKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        sb.append("<title>Vaier — backup recovery sheet</title>\n<style>\n");
        sb.append("body{font:13px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;color:#111;max-width:46em;"
            + "margin:2em auto;padding:0 1em}\n");
        sb.append("h1{font-size:20px;margin:0 0 .2em}h2{font-size:15px;margin:1.8em 0 .4em}\n");
        sb.append(".warn{border:2px solid #111;padding:.8em 1em;margin:1em 0}\n");
        sb.append("table{border-collapse:collapse;width:100%;margin:.4em 0 1.2em}\n");
        sb.append("th{text-align:left;width:9em;vertical-align:top;padding:.25em .6em .25em 0;"
            + "font-weight:600}\n");
        sb.append("td{padding:.25em 0;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;"
            + "word-break:break-all}\n");
        sb.append("pre{background:#f4f4f4;padding:.7em;white-space:pre-wrap;word-break:break-all}\n");
        // It exists to be printed. Nothing here should tempt a browser into keeping it.
        sb.append("@media print{body{margin:0;max-width:none}.warn{border-color:#000}}\n");
        sb.append("</style></head><body>\n");

        sb.append("<h1>Vaier — backup recovery sheet</h1>\n");
        sb.append("<p>Everything needed to read this fleet's backups <em>without Vaier</em>. "
            + "Print it and keep the paper somewhere the fleet is not.</p>\n");

        sb.append("<div class=\"warn\"><strong>Do not store this on any machine in the fleet.</strong> "
            + "Every passphrase below is in the clear, which is what makes the sheet work on the day there is "
            + "no Vaier left to ask. Kept on a machine Vaier protects, it is not a safeguard — it is a second "
            + "copy of the problem, next to the first.</div>\n");

        if (server == null) {
            sb.append("<p>There is <strong>no backup server</strong> in this fleet, so nothing is being "
                + "backed up and there is nothing to recover.</p>\n</body></html>\n");
            return sb.toString();
        }

        sb.append("<h2>Where the backups are</h2>\n<table>\n");
        row(sb, "Machine", server.machineName());
        row(sb, "Reached at", server.host() + ":" + server.sshPort());
        row(sb, "Borg user", server.borgUser());
        sb.append("</table>\n");

        sb.append("<h2>Repositories</h2>\n");
        if (repositories == null || repositories.isEmpty()) {
            sb.append("<p>No repositories yet — nothing has been backed up.</p>\n");
        } else {
            for (BackupRepository repo : repositories) {
                sb.append("<table>\n");
                row(sb, "Backups of", machineFor(repo, jobs).orElse("no machine (kept, not added to)"));
                row(sb, "Repository", repo.borgRepoUrl(server));
                // A blank here would read as "no passphrase needed" and send someone away from the one
                // repository that actually needs attention.
                row(sb, "Passphrase", repo.passphrase() == null || repo.passphrase().isBlank()
                    ? "NOT STORED — Vaier does not hold this one; find it yourself before you need it"
                    : repo.passphrase());
                sb.append("</table>\n");
            }
        }

        sb.append("<h2>Reading an archive with no Vaier</h2>\n");
        sb.append("<p>On any machine with borg installed and network to the server above:</p>\n");
        sb.append("<pre>export BORG_PASSPHRASE='the passphrase above'\n"
            + "borg list   'the repository above'\n"
            + "borg extract 'the repository above'::ARCHIVE-NAME</pre>\n");
        sb.append("<p>It extracts into the current directory. Nothing else is needed — not this fleet, "
            + "not the Vaier server, not the key below.</p>\n");

        sb.append("<h2>Vaier's own configuration key</h2>\n");
        sb.append("<p>The archives above open with their passphrases alone. This key is for the step after: "
            + "restoring Vaier itself from one of them yields a configuration whose stored credentials, AWS "
            + "secret and passphrases are all ciphertext, and this is what decrypts them.</p>\n<table>\n");
        row(sb, "Config key", configKey == null || configKey.isBlank() ? "NOT AVAILABLE" : configKey);
        sb.append("</table>\n");

        sb.append("</body></html>\n");
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

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th>").append(escape(label)).append("</th><td>")
            .append(escape(value)).append("</td></tr>\n");
    }

    /** A passphrase is arbitrary text landing in HTML; it is escaped like any other untrusted value. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
