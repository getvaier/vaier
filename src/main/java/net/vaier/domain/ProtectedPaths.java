package net.vaier.domain;

import java.util.List;

/**
 * What a machine actually backs up: its {@link SourcePaths} <em>minus</em> its {@link Excludes}. This is the
 * whole "is this path backed up?" answer in one value object — the two halves are meaningless apart, and
 * asking only the source paths is how an excluded folder once kept wearing a shield it had lost.
 *
 * <p>The Explorer marks every browsed entry from this verdict, computed once server-side. Neither half of the
 * rule is ever re-implemented in the browser. The two badge questions are {@link #isBackedUp} (full shield)
 * and {@link #containsBackedUp} (half shield); {@link #covers} is the narrower "is this path part of the
 * backup at all", which a folder can answer yes to while still having holes in it.
 */
public record ProtectedPaths(SourcePaths sources, Excludes excludes) {

    /** The protection of a machine that backs up {@code sources} except for {@code excludes}. */
    public static ProtectedPaths of(SourcePaths sources, Excludes excludes) {
        return new ProtectedPaths(sources, excludes);
    }

    /** A machine that backs up nothing — what a machine with no job protects, and what the past shows. */
    public static ProtectedPaths none() {
        return new ProtectedPaths(SourcePaths.of(List.of()), Excludes.none());
    }

    /**
     * Whether {@code path} is <b>backed up</b>: a source path covers it and no exclusion carves it back out.
     * An excluded path is not backed up however broadly its ancestor is protected — borg walks past it, so
     * saying otherwise would be a claim about data that is not in any archive.
     */
    public boolean covers(String path) {
        return sources.covers(path) && !excludes.excludes(path);
    }

    /**
     * Whether {@code path} <b>contains backed up</b> content without being backed up itself: some source path
     * lives strictly inside it and is not excluded. A folder whose only protected content has been excluded
     * holds nothing after all, and wears no half shield.
     */
    public boolean enclosesUnder(String path) {
        if (covers(path)) {
            return false;   // backed up and contains-backed-up are mutually exclusive
        }
        return sources.paths().stream()
            .filter(member -> !excludes.excludes(member))
            .anyMatch(member -> isStrictlyUnder(path, member));
    }

    /**
     * Whether {@code path} is <b>backed up</b> in the sense a full shield promises: it is covered, and there
     * is no hole anywhere inside it. This — not {@link #covers} — is the badge question.
     *
     * <p>The distinction is the Colina 27 / Apalveien 5 report. {@code /home} was protected with the openhab
     * logs folder excluded inside it, and {@code covers("/home")} is quite correctly true: {@code /home} is
     * part of the backup. But a full shield on a folder is read as "everything under here is in the archive",
     * and with a hole inside it that is a claim about data borg walks straight past — the same class of lie as
     * a run reporting success while skipping files. A holed folder is therefore demoted to the half shield:
     * it holds backed-up content without being whole, which is exactly what {@link #containsBackedUp} says.
     *
     * <p>A file has nothing inside it, so this is identical to {@link #covers} for one — the rule only ever
     * demotes a folder that really does contain a hole.
     */
    public boolean isBackedUp(String path) {
        return covers(path) && !excludes.anyStrictlyInside(path);
    }

    /**
     * Whether {@code path} <b>contains backed up</b> content without being wholly backed up itself — the half
     * shield. Two ways to earn it, and they are the same statement about the data: unexcluded protected
     * content lives strictly inside it ({@link #enclosesUnder}), or it is covered but holed by an exclusion
     * inside it. Mutually exclusive with {@link #isBackedUp} by construction, so no caller has to re-guard it.
     */
    public boolean containsBackedUp(String path) {
        if (isBackedUp(path)) {
            return false;
        }
        return enclosesUnder(path) || covers(path);
    }

    /** Whether this protects nothing at all. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    private static boolean isStrictlyUnder(String ancestor, String member) {
        String normalized = PathCoverage.normalize(ancestor);
        return normalized != null && !normalized.equals(member) && PathCoverage.covers(normalized, member);
    }
}
