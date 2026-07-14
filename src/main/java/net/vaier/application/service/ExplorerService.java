package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
import net.vaier.domain.FileEntry;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForBrowsingRemoteFiles;
import net.vaier.domain.port.ForBrowsingRemoteFiles.DirectoryListing;
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
public class ExplorerService implements BrowseFilesUseCase {

    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForBrowsingRemoteFiles forBrowsingRemoteFiles;
    private final ForTrackingHostKeys forTrackingHostKeys;

    @Override
    public List<FileEntry> listDirectory(String machineName, String path) {
        // The trust boundary: the browser's path becomes a real path here, or it is refused — before a
        // machine is resolved and long before a connection is opened.
        String directory = FileEntry.normalisePath(path);

        SshTarget target = forResolvingSshTargets.resolve(machineName);
        DirectoryListing listing = forBrowsingRemoteFiles.list(target, directory);
        pinOnFirstUse(machineName, target, listing.hostKeyFingerprint());

        log.debug("Listed {} on {}", directory, machineName);
        return FileEntry.listing(listing.entries());
    }

    /**
     * Trust-on-first-use: a machine may be browsed before a terminal was ever opened on it, so the first
     * SFTP connect is where its host key gets pinned — the same rule the shell and exec paths follow.
     */
    private void pinOnFirstUse(String machineName, SshTarget target, String presentedFingerprint) {
        if (target.needsPinning(presentedFingerprint)) {
            forTrackingHostKeys.pin(machineName, presentedFingerprint);
        }
    }
}
