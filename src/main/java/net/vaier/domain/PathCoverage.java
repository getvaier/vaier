package net.vaier.domain;

/**
 * The one path-containment rule the fleet-backup protection language is built on: an absolute path
 * <em>covers</em> itself and everything beneath it. {@link SourcePaths} uses it to keep the protected set a
 * minimal cover, {@link Excludes} to collapse redundant exclusions, and {@link ProtectedPaths} to answer
 * "is this backed up?" — one rule, stated once, so the three can never drift apart.
 *
 * <p>It is deliberately pure text: no filesystem, no globbing. A borg fnmatch pattern is not a path, so
 * containment simply never claims anything about one (see {@link Excludes}).
 */
final class PathCoverage {

    private PathCoverage() {
    }

    /** Whether {@code ancestor} equals or is a strict ancestor of {@code path}. */
    static boolean covers(String ancestor, String path) {
        if (ancestor.equals(path)) {
            return true;
        }
        String prefix = ancestor.equals("/") ? "/" : ancestor + "/";
        return path.startsWith(prefix);
    }

    /** Trim, return null for blank, strip a single trailing slash (except root). */
    static String normalize(String path) {
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

    /** Whether {@code pattern} is an absolute path (and so something containment can reason about). */
    static boolean isAbsolutePath(String pattern) {
        return pattern != null && pattern.startsWith("/");
    }
}
