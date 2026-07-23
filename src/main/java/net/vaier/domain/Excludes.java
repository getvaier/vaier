package net.vaier.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The holes a {@link BackupJob} carves out of its {@link SourcePaths} — the borg {@code --exclude} patterns a
 * run applies. This is how "stop backing up X" is honoured when X sits <em>inside</em> a path that stays
 * protected: the ancestor keeps covering everything else, and X is recorded here so borg walks past it.
 *
 * <p>Like the protected set, exclusions are kept as a <strong>minimal cover</strong>: excluding
 * {@code /home/openhab} already keeps {@code /home/openhab/logs} out, so the deeper entry is dropped rather
 * than accumulated. Duplicates collapse, blanks are ignored and a trailing slash is stripped so ancestry
 * still collapses.
 *
 * <p><b>A pattern is not a path.</b> A job may also carry borg fnmatch patterns ({@code *.tmp}) set in the
 * job editor. Containment can say nothing about those — Vaier cannot tell which files a glob bites into — so
 * they are carried verbatim, never collapsed into a path and never {@link #prunedTo pruned} away. Only
 * absolute paths take part in the coverage rules here.
 *
 * <p>What an exclusion means relative to a protection is a business decision, so it lives here on a domain
 * value object beside {@link SourcePaths} rather than in a service.
 */
public record Excludes(List<String> patterns) {

    public Excludes {
        patterns = List.copyOf(patterns);
    }

    /** Normalize {@code raw} into a minimal-cover set of exclusions: trimmed, blank-free, deduped, collapsed. */
    public static Excludes of(Collection<String> raw) {
        return new Excludes(minimalCover(clean(raw)));
    }

    /** No exclusions at all — everything the protected set covers is backed up. */
    public static Excludes none() {
        return new Excludes(List.of());
    }

    /** A new set additionally excluding {@code toAdd}, re-normalized so nothing redundant accumulates. */
    public Excludes excluding(Collection<String> toAdd) {
        List<String> union = new ArrayList<>(patterns);
        union.addAll(clean(toAdd));
        return Excludes.of(union);
    }

    /**
     * Whether {@code path} is excluded: it equals an exclusion, or lives beneath one (borg never walks into
     * an excluded directory, so everything under it is out too). Glob patterns never match here — see the
     * class note.
     */
    public boolean excludes(String path) {
        String candidate = PathCoverage.normalize(path);
        if (candidate == null) {
            return false;
        }
        return patterns.stream()
            .filter(PathCoverage::isAbsolutePath)
            .anyMatch(pattern -> PathCoverage.covers(pattern, candidate));
    }

    /**
     * Whether a hole sits <b>strictly inside</b> {@code path} — an exclusion deeper than it, so the folder is
     * protected but not whole. This is what separates "everything under here is in the archive" from "most of
     * it is", and it is the difference between an honest full shield and a false one.
     *
     * <p>An exclusion at {@code path} itself does not count: that folder is not partly backed up, it is out —
     * {@link #excludes} is the question for that. Glob patterns never count either, for the reason in the
     * class note: Vaier cannot tell which files {@code *.tmp} bites into, so it can neither claim the folder
     * is holed nor pretend otherwise, and inventing a verdict from a pattern would be a guess dressed as fact.
     */
    public boolean anyStrictlyInside(String path) {
        String candidate = PathCoverage.normalize(path);
        if (candidate == null) {
            return false;
        }
        return patterns.stream()
            .filter(PathCoverage::isAbsolutePath)
            .anyMatch(pattern -> !pattern.equals(candidate) && PathCoverage.covers(candidate, pattern));
    }

    /**
     * A new set with every exclusion that <em>conflicts</em> with {@code nowProtected} dropped — the rule that
     * keeps "stop backing up X" then "back up X" honest.
     *
     * <p>Conflict runs both ways. An exclusion at or under a freshly protected path is plainly stale. An
     * exclusion that is an <em>ancestor</em> of it conflicts too: keeping it would silently skip the very
     * folder the operator just asked for, and a folder that looks protected but is quietly passed over is the
     * exact lie this vocabulary exists to prevent. The newer, explicit instruction therefore wins — backing up
     * a little more than asked is safe; silently backing up less is not.
     */
    public Excludes clearedFor(Collection<String> nowProtected) {
        List<String> protectedPaths = clean(nowProtected);
        List<String> kept = patterns.stream()
            .filter(pattern -> !PathCoverage.isAbsolutePath(pattern)
                || protectedPaths.stream().noneMatch(p -> conflicts(pattern, p)))
            .toList();
        return new Excludes(kept);
    }

    /**
     * A new set holding only the exclusions that still mean something against {@code sources}: an exclusion
     * carving a hole inside a path nothing protects any more is dead weight, and would resurface as a
     * confusing rule the day that path is protected again. Glob patterns are always kept.
     */
    public Excludes prunedTo(SourcePaths sources) {
        List<String> kept = patterns.stream()
            .filter(pattern -> !PathCoverage.isAbsolutePath(pattern) || sources.covers(pattern))
            .toList();
        return new Excludes(kept);
    }

    /** Whether nothing is excluded. */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /** Whether the two absolute paths overlap in either direction. */
    private static boolean conflicts(String one, String other) {
        return PathCoverage.covers(one, other) || PathCoverage.covers(other, one);
    }

    /** Cleaned, deduped, and reduced to only the exclusions no other exclusion already covers. */
    private static List<String> minimalCover(List<String> cleaned) {
        Set<String> unique = new LinkedHashSet<>(cleaned);
        List<String> result = new ArrayList<>();
        for (String candidate : unique) {
            boolean coveredByAnother = unique.stream()
                .anyMatch(other -> !other.equals(candidate)
                    && PathCoverage.isAbsolutePath(other) && PathCoverage.isAbsolutePath(candidate)
                    && PathCoverage.covers(other, candidate));
            if (!coveredByAnother) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Trim and drop blanks; a trailing slash goes so ancestry collapses. Non-absolute patterns pass through. */
    private static List<String> clean(Collection<String> raw) {
        List<String> cleaned = new ArrayList<>();
        for (String pattern : raw) {
            String trimmed = PathCoverage.normalize(pattern);
            if (trimmed != null) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }
}
