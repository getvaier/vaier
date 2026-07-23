package net.vaier.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source files a {@link BackupRun} could not read, as borg itself reported them — the evidence behind an
 * {@link BackupRunStatus#INCOMPLETE} run. Each one is a file that was inside the job's protected paths and is
 * <b>not</b> in the archive, so this is the difference between "the backup ran" and "the backup got the data".
 *
 * <p>borg names a denial on the file it hit it on, one line per file, in a handful of shapes across versions:
 * <pre>
 *   /home/nut-http/logs/x.log: open: [Errno 13] Permission denied: 'x.log'
 *   /var/lib/docker/volumes/db/_data: scandir: [Errno 13] Permission denied: '_data'
 *   /home/geir/.ssh/id_ed25519: [Errno 13] Permission denied: 'id_ed25519'
 * </pre>
 * All three are the same fact, so all three are read the same way: everything before the errno marker is the
 * path, whatever syscall borg names in between. Matching keys on {@code [Errno 13]} rather than on the words
 * "permission denied" precisely so a <em>human</em> sentence that happens to contain those words — borg's own
 * "2 files skipped (permission denied)" roll-up, an operator's note — can never be mistaken for a lost file.
 * Reading borg's output is a domain rule, so it lives here beside the other {@code parse*} rules in
 * {@link BorgCommand}, not in a service or a view.
 *
 * <p><b>Why a sample and a count, rather than every path.</b> A non-root run over a busy {@code /home} emits
 * thousands of these lines, and this value ends up in a run's UI pane and in an admin's inbox. Retaining every
 * path would put an unbounded blob into both, so only the first {@link #SAMPLE_LIMIT} are kept — enough to
 * recognise <em>which</em> data is missing — while {@link #total} stays exact, because "how much did I lose"
 * is the number that decides whether an operator acts.
 *
 * @param sample the first {@link #SAMPLE_LIMIT} distinct unreadable paths, in the order borg reported them
 * @param total  how many distinct files could not be read (never capped)
 */
public record UnreadableFiles(List<String> sample, int total) {

    /** How many paths are retained for display; the count is not capped. See the class note on why. */
    public static final int SAMPLE_LIMIT = 10;

    /** No file was denied — a run that got everything it asked for. */
    private static final UnreadableFiles NONE = new UnreadableFiles(List.of(), 0);

    /**
     * One borg denial line: the path, then optionally the syscall borg was denied on, then the errno marker.
     * The path group is lazy so it stops at the earliest point from which the rest of the line still matches —
     * which keeps a path containing {@code ": "} intact rather than truncating it at the first colon.
     */
    private static final Pattern DENIAL = Pattern.compile("^(.+?): (?:[A-Za-z_]+: )?\\[Errno 13\\]");

    public UnreadableFiles {
        sample = sample == null ? List.of() : List.copyOf(sample);
    }

    /**
     * Read {@code borgOutput} — a run's captured output — into the files it says could not be read. Total,
     * never throwing: null, blank, or output with no denial line all read as {@link #NONE}. The same path
     * denied more than once (borg can hit a file on more than one pass) counts once, because an operator
     * asking "how many files did I lose" must be told about files, not about lines.
     */
    public static UnreadableFiles from(String borgOutput) {
        if (borgOutput == null || borgOutput.isBlank()) {
            return NONE;
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String line : borgOutput.split("\\R")) {
            Matcher m = DENIAL.matcher(line.strip());
            if (m.find()) {
                paths.add(m.group(1));
            }
        }
        if (paths.isEmpty()) {
            return NONE;
        }
        List<String> kept = new ArrayList<>(paths).subList(0, Math.min(SAMPLE_LIMIT, paths.size()));
        return new UnreadableFiles(List.copyOf(kept), paths.size());
    }

    /** Whether any source file was left out of the archive — the predicate that makes a run incomplete. */
    public boolean any() {
        return total > 0;
    }

    /**
     * The operator's account of what is missing, for the admin alert and anywhere else a person is told: how
     * many files, which ones (bounded — and said to be bounded, so ten paths under a count of 250 never reads
     * as "ten files lost"), and the one setting that would have read them. Naming the fix beside the problem
     * is the point: a list of paths alone tells an operator they lost data but not what to do about it.
     * Returns an empty string when nothing was denied, so a caller can append it unconditionally.
     */
    public String report() {
        if (!any()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append(total).append(total == 1 ? " file could not be read" : " files could not be read")
            .append(" on this machine, so they are NOT in the archive");
        if (total > sample.size()) {
            out.append(" (showing the first ").append(SAMPLE_LIMIT).append(")");
        }
        out.append(":\n");
        for (String path : sample) {
            out.append("  ").append(path).append("\n");
        }
        out.append("\nTurn on Back up as root for this job so borg reads files owned by other users.\n");
        return out.toString();
    }
}
