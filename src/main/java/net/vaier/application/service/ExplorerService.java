package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.DeleteFileUseCase;
import net.vaier.application.DownloadFileUseCase;
import net.vaier.application.ResolveFileCoordinateUseCase;
import net.vaier.domain.FileEntry;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
import net.vaier.domain.port.ForBrowsingRemoteFiles.RemoteStat;
import net.vaier.domain.port.ForMountingArchives;
import net.vaier.domain.port.ForResolvingSftpRoots;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Explorer domain service — one file tree spanning the fleet. Vaier sits at the VPN hub and is the
 * only node with SSH to every machine, so it is the only place such a tree can be assembled.
 *
 * <p>It orchestrates, it does not decide: the domain says what a browsable path is and what order a
 * directory reads in ({@link FileEntry}), the driven ports resolve the machine to an SSH target and read
 * the directory over SFTP. The path arrives from the browser, so it is normalised <em>before</em> Vaier
 * opens any connection — a hostile path is refused here, not on the machine.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExplorerService
    implements BrowseFilesUseCase, ResolveFileCoordinateUseCase, DownloadFileUseCase, DeleteFileUseCase {

    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForBrowsingRemoteFiles forBrowsingRemoteFiles;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForResolvingSftpRoots forResolvingSftpRoots;
    private final ForMountingArchives forMountingArchives;

    @Override
    public MachineDirectory listDirectory(String machineName, String path, String at) {
        // The trust boundary: the browser's path becomes a real path here, or it is refused — before a
        // machine is resolved, an archive is mounted, or any connection is opened. This holds identically in
        // the past: a climb above the root is refused before slice D does anything.
        String requested = path == null || path.isBlank() ? null : FileEntry.normalisePath(path);

        SshTarget target = forResolvingSshTargets.resolve(machineName);
        SftpRoot root = forResolvingSftpRoots.rootFor(machineName, target);

        // Present or past, the service maps a requested coordinate down to the real machine path SFTP must be
        // asked for, and the answered entries back up to the requested coordinate. In the present that is the
        // jail (down and back). In the past it is the archive mount, composed on top of the jail — the service
        // asks the mount port for a mountpoint and never learns what borg is.
        return at == null || at.isBlank()
            ? listPresent(machineName, target, root, requested)
            : listPast(machineName, target, root, requested, at);
    }

    /** The live filesystem: down into the jail to read, back onto the machine's own coordinates. */
    private MachineDirectory listPresent(String machineName, SshTarget target, SftpRoot root, String requested) {
        // A machine's file tree begins at its SFTP root, not at "/": on a chrooted machine "/" is not a path
        // SFTP can be asked about at all. So a browser that names no path is asking where the tree begins.
        String directory = requested != null ? requested : root.path();

        DirectoryListing listing = forBrowsingRemoteFiles.list(target, root.toJailPath(directory));
        pinOnFirstUse(machineName, target, listing.hostKeyFingerprint());

        log.debug("Listed {} on {}", directory, machineName);
        return new MachineDirectory(root, directory, FileEntry.listing(root.anchor(listing.entries())));
    }

    /**
     * The past: mount the archive {@code at} on the machine and list the same coordinate inside it. The
     * archive captured absolute machine paths, so its tree begins at "/" and a bare browse begins there. The
     * archive path maps under the mountpoint to the real machine path, which then maps down through the jail
     * exactly as the present does; the answered entries map back the other way, mount then jail.
     */
    private MachineDirectory listPast(String machineName, SshTarget target, SftpRoot root, String requested,
                                      String at) {
        MountedArchive mounted = forMountingArchives.mount(machineName, at);

        String directory = requested != null ? requested : "/";
        String machinePath = mounted.machinePath(directory);

        DirectoryListing listing = forBrowsingRemoteFiles.list(target, root.toJailPath(machinePath));
        pinOnFirstUse(machineName, target, listing.hostKeyFingerprint());

        // Two maps home: the jail anchors entries onto real machine paths, then the mount strips itself off
        // to leave the file's own path in the archive — the same coordinate the live tree uses.
        List<FileEntry> archiveEntries = mounted.anchor(root.anchor(listing.entries()));
        log.debug("Listed {} in archive {} on {}", directory, at, machineName);
        return new MachineDirectory(SftpRoot.NONE, directory, FileEntry.listing(archiveEntries), at);
    }

    /**
     * Resolve a file's coordinate to the SFTP target and the real on-host path — the same mapping
     * {@link #listDirectory} applies, factored out so the Transfer relay and the download path do not carry a
     * second copy of it. The path is required and concrete: a coordinate to move or download is a real file,
     * never "wherever the tree begins". The trust boundary still stands in front — a hostile path is refused
     * here, before a machine is resolved or an archive mounted.
     */
    @Override
    public ResolvedFileCoordinate resolve(String machineName, String path, String at) {
        String requested = FileEntry.normalisePath(path);
        SshTarget target = forResolvingSshTargets.resolve(machineName);
        SftpRoot root = forResolvingSftpRoots.rootFor(machineName, target);
        return new ResolvedFileCoordinate(target, sftpPathFor(machineName, root, requested, at));
    }

    /**
     * Where SFTP must actually be asked for a requested coordinate: the jail path in the present, and the
     * archive-mount path composed over the jail in the past. This is the one down-mapping the whole Explorer
     * shares — {@link #listPresent}/{@link #listPast} map a browse the same way.
     */
    private String sftpPathFor(String machineName, SftpRoot root, String requested, String at) {
        if (at == null || at.isBlank()) {
            return root.toJailPath(requested);
        }
        MountedArchive mounted = forMountingArchives.mount(machineName, at);
        return root.toJailPath(mounted.machinePath(requested));
    }

    /**
     * Delete a file or directory in the present — the Explorer's one destructive mutate (slice 5). The trust
     * boundary stands in front exactly as it does for a browse: the browser's path becomes a real path here —
     * non-absolute, or climbing above the root, is refused before a machine is resolved or any connection is
     * opened. Then the same jail down-mapping every other operation shares is applied, with one added refusal
     * the domain owns — the machine's SFTP root itself is never deletable ({@link SftpRoot#toDeletableJailPath}).
     * There is no {@code at}: only the live filesystem is ever deleted, because the past is read-only.
     */
    @Override
    public void delete(String machineName, String path) {
        String requested = FileEntry.normalisePath(path);
        SshTarget target = forResolvingSshTargets.resolve(machineName);
        SftpRoot root = forResolvingSftpRoots.rootFor(machineName, target);

        forBrowsingRemoteFiles.delete(target, root.toDeletableJailPath(requested));
        log.info("Deleted {} on {}", requested, machineName);
    }

    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String ZIP = "application/zip";

    /**
     * Open a file or directory for download: resolve its coordinate, stat it, and branch. A file streams
     * as-is; a directory streams as a zip of its whole tree ({@link #openDirectoryForDownload}). Either way
     * the bytes are streamed lazily by the returned writer, opening the SFTP read(s) only when invoked, so
     * memory stays flat. A download is a read, so {@code at} may name an archive — the past is fine, and so
     * is zipping it.
     */
    @Override
    public Download openForDownload(String machineName, String path, String at) {
        ResolvedFileCoordinate coordinate = resolve(machineName, path, at);
        RemoteStat stat = forBrowsingRemoteFiles.stat(coordinate.target(), coordinate.path());
        if (stat.directory()) {
            return openDirectoryForDownload(machineName, path, coordinate);
        }
        String filename = basename(coordinate.path());
        log.debug("Opening {} on {} for download ({} bytes)", filename, machineName, stat.sizeBytes());
        return new Download(filename, stat.sizeBytes(), OCTET_STREAM,
            out -> forBrowsingRemoteFiles.download(coordinate.target(), coordinate.path(), out));
    }

    /**
     * A directory, zipped on the way to the browser. The zip filename is the directory's own basename — the
     * requested path's, not the (possibly jail- or mount-mapped) coordinate SFTP was actually asked for — or,
     * when the requested path is a root with no basename of its own ({@code /}), the machine's name stands in
     * for it. The size is not known ahead of time: a zip's byte count is not the sum of the files it holds,
     * so {@code Content-Length} must not be guessed at and is reported as {@code -1}.
     */
    private Download openDirectoryForDownload(String machineName, String requestedPath,
                                               ResolvedFileCoordinate coordinate) {
        String dirname = basename(FileEntry.normalisePath(requestedPath));
        String zipFilename = (dirname.isEmpty() ? machineName : dirname) + ".zip";
        log.debug("Opening directory {} on {} for a zip download", coordinate.path(), machineName);
        return new Download(zipFilename, -1, ZIP,
            out -> streamZip(coordinate.target(), coordinate.path(), out));
    }

    /** The last segment of a path — a download's filename, or a directory's own name inside its zip. */
    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Walk {@code rootPath} on {@code target} and stream it into a zip written to {@code out} — flat memory,
     * one file at a time straight into the zip, never buffering the archive whole. The walk itself, and every
     * file read within it, runs over a <b>single</b> SFTP connection ({@link ForBrowsingRemoteFiles#walkTree})
     * — a per-file reconnect over the VPN is what once timed the download out mid-stream into a corrupt zip.
     *
     * <p>The service owns the zip; the adapter never learns what one is. The walk hands each file's bytes and
     * each empty directory to the visitor, named relative to the zip's root (forward slashes, so the zip
     * unpacks the same way on every OS); the visitor turns them into zip entries. A file the SSH user cannot
     * read, or that vanished mid-walk, is skipped by the walk before its stream is ever opened — so an
     * unreadable file simply does not appear in the archive, and no half-written entry can corrupt it. An
     * empty directory becomes a zip directory entry of its own, the only way a zip can carry a folder with
     * nothing in it.
     */
    private void streamZip(SshTarget target, String rootPath, OutputStream out) {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            forBrowsingRemoteFiles.walkTree(target, rootPath, new ForBrowsingRemoteFiles.RemoteTreeVisitor() {
                @Override
                public void directory(String relativePath) throws IOException {
                    zip.putNextEntry(new ZipEntry(relativePath + "/"));
                    zip.closeEntry();
                }

                @Override
                public void file(String relativePath, InputStream content) throws IOException {
                    zip.putNextEntry(new ZipEntry(relativePath));
                    content.transferTo(zip);
                    zip.closeEntry();
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Trust-on-first-use: a machine may be browsed before a terminal was ever opened on it, so the first
     * SFTP connect is where its host key gets pinned — the same rule the shell and exec paths follow, from
     * the one copy that lives on {@link SshTarget#pinOnFirstUse}.
     */
    private void pinOnFirstUse(String machineName, SshTarget target, String presentedFingerprint) {
        target.pinOnFirstUse(machineName, presentedFingerprint, forTrackingHostKeys);
    }
}
