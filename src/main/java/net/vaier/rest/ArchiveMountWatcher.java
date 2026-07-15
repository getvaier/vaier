package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.domain.port.ForMountingArchives;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps idle archive mounts off the fleet. Browsing the past ({@code Explorer} slice D) mounts a borg
 * archive as a read-only FUSE filesystem on its machine; each mount holds a mount and a borg process, so it
 * must not leak. This watcher periodically asks {@link ForMountingArchives#unmountIdle} to release every
 * mount left untouched beyond {@link #IDLE_WINDOW_MS}, so a mount lives only as long as it is being used.
 *
 * <p>Mirrors {@code BackupServerWatcher}/{@code RemoteDiskWatcher}: the {@code @Scheduled} cadence lives in
 * {@code rest/} while the work happens behind the driven port. It never throws — the port swallows a failed
 * unmount so one stuck host cannot stall the sweep.
 */
@Component
@RequiredArgsConstructor
public class ArchiveMountWatcher {

    /** How long an archive mount may sit unbrowsed before it is released — generous enough for a browse pause. */
    static final long IDLE_WINDOW_MS = 600_000;

    /** How often the sweep runs. Frequent enough that an idle mount is released within a few minutes of the window. */
    private static final long SWEEP_INTERVAL_MS = 120_000;

    private final ForMountingArchives forMountingArchives;

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void releaseIdleMounts() {
        forMountingArchives.unmountIdle(IDLE_WINDOW_MS);
    }
}
