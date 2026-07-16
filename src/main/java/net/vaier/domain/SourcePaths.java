package net.vaier.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The normalized set of absolute paths a {@link BackupJob} protects on its machine. This is the
 * "protected paths" of the just-select-and-back-up flow: an operator selects paths in the Explorer and
 * the set is maintained here so that <strong>no path is a descendant of another</strong> — an ancestor
 * always covers its children, and exact duplicates collapse. Adding {@code /home/geir/docs} when
 * {@code /home/geir} is already protected is a no-op; adding {@code /home} when {@code /home/geir} is
 * protected replaces the child with the broader ancestor.
 *
 * <p>This minimal-cover rule is a business decision about what "protecting a set of paths" means, so it
 * lives here on a domain value object rather than in a service. Every path is trimmed, blanks are
 * ignored, and a non-absolute path (one without a leading {@code /}) is rejected — a source path is used
 * verbatim in a borg {@code create}, so it must be absolute.
 */
public record SourcePaths(List<String> paths) {

    public SourcePaths {
        paths = List.copyOf(paths);
    }

    /** Normalize {@code raw} into a minimal-cover set: trimmed, blank-free, deduped, descendants dropped. */
    public static SourcePaths of(Collection<String> raw) {
        return new SourcePaths(minimalCover(clean(raw)));
    }

    /** A new set additionally protecting {@code toAdd}, re-normalized to the minimal cover. */
    public SourcePaths protecting(Collection<String> toAdd) {
        List<String> union = new ArrayList<>(paths);
        union.addAll(clean(toAdd));
        return SourcePaths.of(union);
    }

    /**
     * A new set with {@code toRemove} (and any descendant of a removed path) dropped. Removal is lenient:
     * blanks are ignored and a path that is not present simply matches nothing.
     */
    public SourcePaths without(Collection<String> toRemove) {
        Set<String> removals = new LinkedHashSet<>(cleanLenient(toRemove));
        List<String> remaining = paths.stream()
            .filter(p -> removals.stream().noneMatch(r -> covers(r, p)))
            .toList();
        return new SourcePaths(remaining);
    }

    /** Whether this set protects nothing. */
    public boolean isEmpty() {
        return paths.isEmpty();
    }

    /**
     * Whether {@code path} is backed up by this set: it equals a member, or is a descendant of one. This is
     * the exact containment {@link #of normalization} uses to drop redundant descendants, exposed so the
     * Explorer can mark each browsed entry that a job already protects — the "is this path backed up?"
     * decision lives here in the domain, computed once server-side, never re-implemented in the browser.
     */
    public boolean covers(String path) {
        String candidate = normalizeOne(path);
        if (candidate == null) {
            return false;
        }
        return paths.stream().anyMatch(member -> covers(member, candidate));
    }

    /**
     * Whether {@code path} <em>contains</em> backed-up content without being backed up itself: some member is
     * a strict descendant of {@code path} (a protected path lives somewhere inside it). This is the "walk down
     * to find the protected files" hint — a folder that merely encloses a source path deeper down, so the
     * Explorer can show a half-shield. The decision is the domain's, computed server-side, never in the browser.
     */
    public boolean enclosesUnder(String path) {
        String candidate = normalizeOne(path);
        if (candidate == null) {
            return false;
        }
        return paths.stream().anyMatch(member -> covers(candidate, member) && !member.equals(candidate));
    }

    /** Cleaned, deduped, and reduced to only the paths not covered by another path in the set. */
    private static List<String> minimalCover(List<String> cleaned) {
        Set<String> unique = new LinkedHashSet<>(cleaned);
        List<String> result = new ArrayList<>();
        for (String candidate : unique) {
            boolean coveredByAnother = unique.stream()
                .anyMatch(other -> !other.equals(candidate) && covers(other, candidate));
            if (!coveredByAnother) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Whether {@code ancestor} equals or is a strict ancestor of {@code path}. */
    private static boolean covers(String ancestor, String path) {
        if (ancestor.equals(path)) {
            return true;
        }
        String prefix = ancestor.equals("/") ? "/" : ancestor + "/";
        return path.startsWith(prefix);
    }

    /** Trim, drop blanks, require absolute, strip a trailing slash. */
    private static List<String> clean(Collection<String> raw) {
        List<String> cleaned = new ArrayList<>();
        for (String path : raw) {
            String trimmed = normalizeOne(path);
            if (trimmed == null) {
                continue;
            }
            if (!trimmed.startsWith("/")) {
                throw new IllegalArgumentException("Source path must be absolute: " + path);
            }
            cleaned.add(trimmed);
        }
        return cleaned;
    }

    /** Like {@link #clean} but tolerant of non-absolute paths (used for removal, which just won't match). */
    private static List<String> cleanLenient(Collection<String> raw) {
        List<String> cleaned = new ArrayList<>();
        for (String path : raw) {
            String trimmed = normalizeOne(path);
            if (trimmed != null) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    /** Trim, return null for blank, strip a single trailing slash (except root). */
    private static String normalizeOne(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
