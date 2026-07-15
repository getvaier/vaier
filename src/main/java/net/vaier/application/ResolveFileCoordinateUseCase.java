package net.vaier.application;

import net.vaier.domain.SshTarget;

/**
 * Map a file's Explorer coordinate — (machine, path, point-in-time) — down to what SFTP must actually be
 * asked for: the machine's connectable {@link SshTarget} and the real on-host path. This is the very
 * mapping {@code ExplorerService} already applies when it browses (the SFTP jail in the present, the
 * archive mount composed over the jail in the past), exposed as a narrow seam so the Transfer relay and
 * the download path resolve coordinates the one way, never a second copy that could drift.
 *
 * <p>Cross-domain callers (the {@code rest/} Transfer runner) depend on this interface, never on
 * {@code ExplorerService} directly — the hex boundary holds.
 */
public interface ResolveFileCoordinateUseCase {

    /**
     * Resolve {@code path} on {@code machineName} at time {@code at} to the SFTP target and the real path to
     * ask it for. With {@code at} null the present is resolved (jail-mapped); with {@code at} naming an
     * archive the past is resolved (mounted, then jail-mapped) — a read of the past, which a transfer may use
     * as its source (a restore).
     *
     * @param path the file or directory, at the machine's own true coordinates — required (a coordinate to
     *             move or download is always concrete, never "wherever the tree begins")
     * @throws IllegalArgumentException when {@code path} is not a browsable absolute path
     * @throws net.vaier.domain.PathOutsideSftpRootException when {@code path} is above the machine's SFTP root
     * @throws net.vaier.domain.NotFoundException when no machine bears the name
     */
    ResolvedFileCoordinate resolve(String machineName, String path, String at);

    /** A coordinate resolved for SFTP: where to connect ({@code target}) and the real path to ask for. */
    record ResolvedFileCoordinate(SshTarget target, String path) {
    }
}
