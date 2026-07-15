package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
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

import java.util.List;

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
public class ExplorerService implements BrowseFilesUseCase, ResolveFileCoordinateUseCase, DownloadFileUseCase {

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
     * Open a file for download: resolve its coordinate, stat it, and refuse a directory up front (a folder
     * download is not supported yet) so the browser gets a clean 400 rather than a broken stream. The bytes
     * are streamed lazily by the returned writer, opening the SFTP read only when invoked, so memory stays
     * flat. A download is a read, so {@code at} may name an archive — the past is fine.
     */
    @Override
    public Download openForDownload(String machineName, String path, String at) {
        ResolvedFileCoordinate coordinate = resolve(machineName, path, at);
        RemoteStat stat = forBrowsingRemoteFiles.stat(coordinate.target(), coordinate.path());
        if (stat.directory()) {
            throw new IllegalArgumentException("Downloading a folder isn't supported yet.");
        }
        String filename = basename(coordinate.path());
        log.debug("Opening {} on {} for download ({} bytes)", filename, machineName, stat.sizeBytes());
        return new Download(filename, stat.sizeBytes(),
            out -> forBrowsingRemoteFiles.download(coordinate.target(), coordinate.path(), out));
    }

    /** The last segment of a resolved path — the download's filename. */
    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
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
