package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a machine's SFTP subsystem believes the filesystem begins (#326).
 *
 * <p>On most machines that is simply {@code /} and there is nothing to say. But an SSH daemon may chroot its
 * SFTP subsystem while leaving the exec channel alone — Synology's DSM jails SFTP into {@code /volume1} —
 * and then one directory has two names: the Explorer reads {@code /homes/geir} where borg, {@code df} and the
 * operator's own terminal all say {@code /volume1/homes/geir}. The Explorer rests on a file having <b>one</b>
 * coordinate, so the two must be reconciled, and this is the value object that reconciles them.
 *
 * <p><b>How it is learnt.</b> Ask both channels the same question — where is the SSH user's home? — and read
 * the difference: when the SFTP answer is a suffix of the exec answer, what is left in front of it is the
 * jail.
 *
 * <pre>
 *   exec:  $HOME          = /volume1/homes/geir
 *   sftp:  realpath(".")  =         /homes/geir     &lt;- suffix
 *                            =&gt; SFTP root = /volume1
 * </pre>
 *
 * <p><b>And when it cannot be learnt, it is not guessed.</b> Two homes that do not line up mean Vaier does not
 * understand this machine, and a wrong prefix would corrupt every path in its tree — silently, and in both
 * directions. So {@link #resolve} answers empty and the caller falls back to {@link #NONE}, which changes
 * nothing about a machine's paths. Not knowing is safe; guessing is not.
 *
 * <p>The mapping is a <b>decision</b>, not a translation — it is what makes an Explorer path comparable with a
 * backup job's source path and with what the operator sees in a shell — so it lives here on the domain, not in
 * the SFTP adapter and not as a private helper on a service. The service orchestrates; this decides.
 */
public record SftpRoot(String prefix) {

    /** No jail: the machine's SFTP subsystem sees the real root, and a true path is already a jail path. */
    public static final SftpRoot NONE = new SftpRoot("");

    public SftpRoot {
        if (prefix == null) {
            throw new IllegalArgumentException("An SFTP root prefix must not be null (use SftpRoot.NONE)");
        }
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            throw new IllegalArgumentException("An SFTP root prefix must be absolute: " + prefix);
        }
    }

    /**
     * The SFTP root implied by the SSH user's home as the two channels report it, or empty when the two do not
     * line up and Vaier must not invent a mapping.
     *
     * @param execHome the home the exec channel reports — the machine's true coordinate
     * @param sftpHome the home the SFTP subsystem reports — inside the jail, when there is one
     */
    public static Optional<SftpRoot> resolve(String execHome, String sftpHome) {
        Optional<String> exec = absolute(execHome);
        Optional<String> sftp = absolute(sftpHome);
        if (exec.isEmpty() || sftp.isEmpty()) {
            return Optional.empty();
        }
        String trueHome = exec.get();
        String jailedHome = sftp.get();
        if (!trueHome.endsWith(jailedHome)) {
            return Optional.empty();
        }
        // The jailed home starts with "/", so the suffix can only ever break on a path-segment boundary —
        // what remains in front of it is a whole path, never half a directory name.
        String prefix = trueHome.substring(0, trueHome.length() - jailedHome.length());
        return Optional.of(prefix.isEmpty() ? NONE : new SftpRoot(prefix));
    }

    /**
     * The names a jailed SFTP subsystem might know {@code trueHome} by — the home itself first, then each
     * successively shorter tail of it.
     *
     * <p><b>Why this exists.</b> {@link #resolve} needs both channels to name the same directory, and on a
     * well-behaved machine they do: SFTP canonicalises {@code .} to the SSH user's home, as the jail sees it.
     * The NAS does not oblige. Its SFTP subsystem answers {@code /} — the jail root itself, which says nothing
     * about <em>where</em> that root is on the machine. So the home must be <em>found</em> inside the jail
     * instead: it is the one directory both channels genuinely share, and these are the names it could wear.
     *
     * <p><b>Longest first, and the true home first of all.</b> The first candidate a machine can actually see
     * is the least jail that explains it, and on a machine with no jail that is the home itself — visible,
     * matched immediately, resolving to {@link #NONE}. An ordinary machine can therefore never be handed a
     * jail it does not have, which is the property that makes the search safe to run on the whole fleet.
     *
     * <p>{@code /} is never a candidate. It exists on every machine, jailed or not, so offering it as a last
     * resort would let any machine match and be given a jail equal to its entire home — precisely the guess
     * this class exists to refuse.
     */
    public static List<String> jailCandidates(String trueHome) {
        String home = FileEntry.normalisePath(trueHome);
        List<String> candidates = new ArrayList<>();
        for (int cut = 0; cut >= 0 && cut < home.length(); cut = home.indexOf('/', cut + 1)) {
            candidates.add(home.substring(cut));
        }
        return List.copyOf(candidates);
    }

    /** Whether this machine's SFTP subsystem is chrooted somewhere other than the real root. */
    public boolean jailed() {
        return !prefix.isEmpty();
    }

    /** The true path of the root itself — where this machine's file tree begins ({@code /volume1}, or {@code /}). */
    public String path() {
        return jailed() ? prefix : "/";
    }

    /**
     * {@code truePath} as the SFTP channel must be asked for it. A path outside the jail is
     * {@linkplain PathOutsideSftpRootException refused} — never quietly answered with the jail's contents,
     * and never with an empty directory.
     */
    public String toJailPath(String truePath) {
        String path = FileEntry.normalisePath(truePath);
        if (!jailed()) {
            return path;
        }
        if (path.equals(prefix)) {
            return "/";
        }
        if (path.startsWith(prefix + "/")) {
            return path.substring(prefix.length());
        }
        throw new PathOutsideSftpRootException(path, path());
    }

    /** A path the SFTP channel answered with, as the machine's true coordinate — what everything else calls it. */
    public String toTruePath(String jailPath) {
        String path = FileEntry.normalisePath(jailPath);
        if (!jailed()) {
            return path;
        }
        return "/".equals(path) ? prefix : prefix + path;
    }

    /** {@code entries}, as read over SFTP, moved onto their true coordinates. Unjailed, they are already there. */
    public List<FileEntry> anchor(List<FileEntry> entries) {
        if (!jailed()) {
            return entries;
        }
        return entries.stream()
            .map(e -> new FileEntry(e.name(), toTruePath(e.path()), e.directory(), e.sizeBytes(), e.modified()))
            .toList();
    }

    /** A home is usable only if it is an absolute path; anything else tells Vaier nothing about this machine. */
    private static Optional<String> absolute(String home) {
        if (home == null || home.isBlank() || !home.strip().startsWith("/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(FileEntry.normalisePath(home.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
