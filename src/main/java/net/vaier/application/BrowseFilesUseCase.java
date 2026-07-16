package net.vaier.application;

import net.vaier.domain.FileEntry;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SourcePaths;

import java.util.List;

/**
 * Browse a machine's filesystem — the Explorer's read side. Lists what one directory on one machine
 * holds, so the operator can walk the fleet's files from a single tree.
 */
public interface BrowseFilesUseCase {

    /**
     * What the directory at {@code path} on {@code machineName} holds <em>in the present</em>, in listing
     * order — the two-coordinate browse. Equivalent to {@link #listDirectory(String, String, String)} with
     * no archive.
     */
    default MachineDirectory listDirectory(String machineName, String path) {
        return listDirectory(machineName, path, null);
    }

    /**
     * What the directory at {@code path} on {@code machineName} holds, in listing order (directories
     * before files, then by name) — at the machine's <b>true</b> coordinates, the ones {@code df}, borg and
     * the operator's own terminal use.
     *
     * <p>The third coordinate is time. When {@code at} is {@code null} the present is browsed, unchanged.
     * When {@code at} names an archive (by its id), that archive is mounted on the machine and the same
     * {@code path} is listed <em>inside the archive</em> — the machine's files as they were when the archive
     * was written.
     *
     * @param path the directory to list, as the machine itself names it, or {@code null} to begin at the
     *             machine's {@link SftpRoot} — which the browser cannot know until it has asked (in the past,
     *             at the archive root)
     * @param at   the id of the archive to browse the past in, or {@code null} for the live filesystem
     * @throws IllegalArgumentException when {@code path} is not a browsable absolute path
     * @throws net.vaier.domain.PathOutsideSftpRootException when {@code path} is above the machine's SFTP
     *         root, and so unreachable over SFTP however valid a path it is
     * @throws net.vaier.domain.NotFoundException when no machine bears the name
     * @throws net.vaier.domain.NoHostCredentialException when the machine has no credential in the vault
     */
    MachineDirectory listDirectory(String machineName, String path, String at);

    /**
     * One directory on one machine: the {@code entries} it holds, the {@code path} they were read from, the
     * {@link SftpRoot} this machine's file tree begins at, and — when this is a browse of the past — the
     * {@code at} archive id these entries were read from ({@code null} in the present).
     *
     * <p>The root travels with every listing because the browser cannot deduce it. A machine's tree does not
     * necessarily begin at {@code /} — it begins wherever its SFTP subsystem is rooted, and everything above
     * that is unreachable over SFTP. A caller that assumed {@code /} would open the NAS on the one path the
     * NAS cannot answer. The {@code at} coordinate travels too, so the browser can tell a listing of the past
     * from one of the present.
     *
     * <p>{@code protectedPaths} carries the machine's backed-up {@link SourcePaths} so each entry can be marked
     * as protected — the "is this path backed up?" decision the domain owns ({@link SourcePaths#covers}). It is
     * only populated for a present listing; the past is left empty, because an old archive's backup shape is
     * not today's protection.
     */
    record MachineDirectory(SftpRoot root, String path, List<FileEntry> entries, String at,
                            SourcePaths protectedPaths) {

        /** A present-tense listing (no archive coordinate, nothing marked protected). */
        public MachineDirectory(SftpRoot root, String path, List<FileEntry> entries) {
            this(root, path, entries, null, SourcePaths.of(List.of()));
        }

        /** A listing at an archive coordinate — the past, where nothing is marked protected. */
        public MachineDirectory(SftpRoot root, String path, List<FileEntry> entries, String at) {
            this(root, path, entries, at, SourcePaths.of(List.of()));
        }

        /** A present-tense listing carrying the machine's protected paths, so entries can be marked. */
        public MachineDirectory(SftpRoot root, String path, List<FileEntry> entries,
                                SourcePaths protectedPaths) {
            this(root, path, entries, null, protectedPaths);
        }
    }
}
