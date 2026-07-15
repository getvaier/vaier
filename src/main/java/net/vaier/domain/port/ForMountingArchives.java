package net.vaier.domain.port;

import net.vaier.domain.MountedArchive;

/**
 * Driven port for mounting a machine's past — a borg {@link net.vaier.domain.Archive} — as a read-only
 * filesystem on the machine itself, so the Explorer can browse it with the same SFTP code that browses the
 * live tree.
 *
 * <p>This is the seam that keeps {@code ExplorerService} free of borg: it asks "where is this machine's
 * archive mounted?" and gets back a {@link MountedArchive} (a mountpoint plus the coordinate mapping), never
 * a borg command. The adapter behind it resolves the machine's backup repository, mounts the archive on
 * demand (idempotently — an already-mounted archive is reused), and returns where it landed.
 *
 * <p>It is also how the past is <em>released</em>: a mount holds a FUSE mount and a borg process on a fleet
 * machine, so {@link #unmountIdle} sweeps mounts untouched beyond an idle window and unmounts them.
 */
public interface ForMountingArchives {

    /**
     * Mount the archive with id {@code archiveId} on the machine named {@code machineName} and return where
     * it landed. Mount-on-demand and idempotent: a second call for the same archive returns the same
     * {@link MountedArchive} without remounting.
     *
     * @throws IllegalArgumentException when {@code archiveId} is not a well-formed archive id
     * @throws net.vaier.domain.NotFoundException when no machine bears the name
     */
    MountedArchive mount(String machineName, String archiveId);

    /**
     * Unmount every archive mount left untouched for longer than {@code idleWindow}, freeing the FUSE mount
     * and borg process it holds on the fleet machine. Called by a scheduled sweep; never throws.
     *
     * @param idleWindowMillis how long a mount may sit unused before it is released
     */
    void unmountIdle(long idleWindowMillis);
}
