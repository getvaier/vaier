package net.vaier.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The past made browsable: an {@link Archive} mounted as a read-only FUSE filesystem on its own machine
 * (via {@code borg mount}), so the Explorer can walk it with the very same SFTP code that walks the live
 * tree — one browse implementation, two coordinates (path, and time).
 *
 * <p>This value object owns two decisions, and only decisions — no borg, no IO:
 * <ul>
 *   <li><b>Where the archive is mounted.</b> {@link #under} keys the mountpoint by the archive's
 *       <em>id</em> (opaque hex, filesystem-safe) rather than its name, because a borg archive name carries
 *       a {@code :} ({@code colina-2026-07-14T02:00:00}) and cannot be a directory. The id becomes a path
 *       segment, so anything that is not hex is refused before it can reach a real path.</li>
 *   <li><b>How a file's coordinate maps across the mount.</b> borg strips the leading {@code /} when it
 *       mounts, so a file at {@code /home/geir/x} <em>inside</em> the archive is really at
 *       {@code <mountpoint>/home/geir/x} on the machine. The mapping is therefore a trivial prefix — but it
 *       runs through {@link FileEntry#normalisePath}, so a path that would climb above the archive root is
 *       refused in the past <em>exactly</em> as it is in the present, and can never escape the mount onto
 *       the live filesystem.</li>
 * </ul>
 *
 * <p>The mapping is the inverse of {@link SftpRoot}'s: where a jail hides a prefix the machine really has,
 * a mount adds a prefix the archive's paths do not carry. Keeping it here — a decision, not a translation —
 * lets {@code ExplorerService} browse the past without knowing what borg is.
 */
public record MountedArchive(String mountpoint) {

    /** An archive id is opaque hex; nothing else can key a mountpoint, so nothing else is accepted. */
    private static final Pattern ARCHIVE_ID = Pattern.compile("[0-9a-fA-F]+");

    /** The directory, under a machine's backup work dir, that all archive mounts live in. */
    private static final String MOUNTS_DIR = "mounts";

    public MountedArchive {
        if (mountpoint == null || mountpoint.isBlank() || !mountpoint.startsWith("/")) {
            throw new IllegalArgumentException("A mountpoint must be an absolute path: " + mountpoint);
        }
    }

    /**
     * The mount for the archive with id {@code archiveId}, placed under {@code workDir} — the same on-host
     * work dir a job's pass file and run state live in ({@code <workDir>/mounts/<archiveId>}). The id keys
     * it, so a second browse of the same archive derives the same mountpoint and reuses the existing mount.
     *
     * @throws IllegalArgumentException when {@code workDir} is not absolute, or {@code archiveId} is not
     *                                  opaque hex (which alone can be trusted as a bare path segment)
     */
    public static MountedArchive under(String workDir, String archiveId) {
        if (workDir == null || workDir.isBlank() || !workDir.startsWith("/")) {
            throw new IllegalArgumentException("A backup work dir must be an absolute path: " + workDir);
        }
        if (archiveId == null || !ARCHIVE_ID.matcher(archiveId).matches()) {
            throw new IllegalArgumentException("An archive id must be opaque hex: " + archiveId);
        }
        return new MountedArchive(FileEntry.normalisePath(workDir) + "/" + MOUNTS_DIR + "/" + archiveId);
    }

    /**
     * The real path on the machine of the archive file at {@code archiveTruePath} — the mountpoint with the
     * archive path appended. The archive root ({@code /}) is the mountpoint itself. The path is normalised
     * first, so a climb above the archive root is refused, never mapped onto the live machine.
     */
    public String machinePath(String archiveTruePath) {
        String path = FileEntry.normalisePath(archiveTruePath);
        return "/".equals(path) ? mountpoint : mountpoint + path;
    }

    /**
     * The archive coordinate of a real machine path under this mount — the mountpoint stripped back off, so
     * the browser sees the file's own path in the archive, the same one the live tree uses. A path that is
     * not under the mount is refused rather than silently answered.
     */
    public String toArchivePath(String machinePath) {
        String path = FileEntry.normalisePath(machinePath);
        if (path.equals(mountpoint)) {
            return "/";
        }
        if (path.startsWith(mountpoint + "/")) {
            return path.substring(mountpoint.length());
        }
        throw new IllegalArgumentException(path + " is not under the archive mount " + mountpoint);
    }

    /** {@code entries}, as read under the mountpoint, moved back onto their archive coordinates. */
    public List<FileEntry> anchor(List<FileEntry> entries) {
        return entries.stream()
            .map(e -> new FileEntry(e.name(), toArchivePath(e.path()), e.directory(), e.sizeBytes(),
                e.modified()))
            .toList();
    }
}
