package net.vaier.domain;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * One line in the Explorer: a file or a directory on a machine, as read over SFTP. It carries its
 * {@code name}, its absolute {@code path}, whether it is a {@code directory}, its size in bytes and
 * when it was last modified.
 *
 * <p>The rules about file entries live here rather than in the service or the SFTP adapter, because
 * they are decisions, not translation: <b>what counts as a path</b> ({@link #normalisePath}), <b>how a
 * child's path is built</b> from the directory holding it ({@link #in}), and <b>what order a directory
 * is listed in</b> ({@link #listing} — directories before files, then by name).
 *
 * <p>The path is the one value that arrives from the browser, so {@link #normalisePath} is a trust
 * boundary: it accepts only an absolute path, resolves {@code .} and {@code ..}, and refuses anything
 * that climbs above the root or hides a NUL byte. It deliberately does <em>not</em> strip shell
 * metacharacters — Explorer speaks SFTP, a binary protocol with no command line, and {@code $(…)} or a
 * backtick is a perfectly legal Linux filename that must stay reachable.
 */
public record FileEntry(String name, String path, boolean directory, long sizeBytes, Instant modified) {

    /** Directories first, then by name, case-insensitively — how a human expects a folder to read. */
    private static final Comparator<FileEntry> LISTING_ORDER =
        Comparator.comparing(FileEntry::directory, Comparator.reverseOrder())
            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER);

    public FileEntry {
        requireValidName(name);
        path = normalisePath(path);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("A file entry's size must not be negative (was " + sizeBytes + ")");
        }
        Objects.requireNonNull(modified, "A file entry must carry a modified time");
    }

    /**
     * The entry named {@code name} inside the directory at {@code parentPath} — the only way the SFTP
     * adapter builds an entry, so a remote server cannot fabricate a path by answering {@code readdir}
     * with something like {@code ../../etc}: the name is validated as a single segment and joined here.
     */
    public static FileEntry in(String parentPath, String name, boolean directory, long sizeBytes, Instant modified) {
        requireValidName(name);
        String parent = normalisePath(parentPath);
        String path = "/".equals(parent) ? "/" + name : parent + "/" + name;
        return new FileEntry(name, path, directory, sizeBytes, modified);
    }

    /** {@code entries} in listing order: directories before files, then by name, case-insensitively. */
    public static List<FileEntry> listing(Collection<FileEntry> entries) {
        return entries.stream().sorted(LISTING_ORDER).toList();
    }

    /**
     * {@code raw} as a browsable absolute path, or {@link IllegalArgumentException} if it is not one.
     * Collapses repeated slashes, resolves {@code .} and {@code ..}, and drops a trailing slash. A path
     * that climbs above the root is <b>refused, not clamped</b> — silently turning {@code /../etc} into
     * {@code /etc} would answer a request the caller never made and teach it that the climb worked.
     */
    public static String normalisePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A path is required");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A path must not contain a NUL character");
        }
        if (!raw.startsWith("/")) {
            throw new IllegalArgumentException("A path must be absolute (start with \"/\"): " + raw);
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("A path must not climb above the root: " + raw);
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    /** A name is one path segment: never blank, never a separator, never a {@code .} or {@code ..}. */
    private static void requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A file entry must have a name");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A file entry's name must be a single path segment: " + name);
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("A file entry's name must not be \".\" or \"..\"");
        }
    }
}
