package net.vaier.domain;

import java.util.List;

/**
 * What a machine actually backs up: its {@link SourcePaths} <em>minus</em> its {@link Excludes}. This is the
 * whole "is this path backed up?" answer in one value object — the two halves are meaningless apart, and
 * asking only the source paths is how an excluded folder once kept wearing a shield it had lost.
 *
 * <p>The Explorer marks every browsed entry from this verdict, computed once server-side. Neither half of the
 * rule is ever re-implemented in the browser.
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

    /** Whether this protects nothing at all. */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    private static boolean isStrictlyUnder(String ancestor, String member) {
        String normalized = PathCoverage.normalize(ancestor);
        return normalized != null && !normalized.equals(member) && PathCoverage.covers(normalized, member);
    }
}
