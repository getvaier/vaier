package net.vaier.rest;

import net.vaier.domain.port.ForMountingArchives;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The idle-mount sweep: a scheduled watcher that releases archive mounts no one has browsed for a while, so
 * a mount never lingers as a FUSE mount and borg process on a fleet machine. Following the fleet-backup
 * watcher convention, the schedule lives here in {@code rest/} and the mechanism behind the driven port.
 */
class ArchiveMountWatcherTest {

    @Test
    void sweep_releasesArchiveMountsIdleBeyondTheWindow() {
        ForMountingArchives mounts = mock(ForMountingArchives.class);
        ArchiveMountWatcher watcher = new ArchiveMountWatcher(mounts);

        watcher.releaseIdleMounts();

        verify(mounts).unmountIdle(ArchiveMountWatcher.IDLE_WINDOW_MS);
    }
}
