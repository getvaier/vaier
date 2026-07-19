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
 *
 * <p>The fast sweep only knows the in-memory registry, which does not survive a Vaier restart — so a mount
 * left live across a redeploy would linger forever, holding the repository lock and failing that machine's
 * backups. A second, slower {@link #reconcileOrphanMounts} pass asks the fleet what is really mounted and
 * adopts orphans the registry has forgotten. {@code @Scheduled(fixedDelay)} fires its first run shortly
 * after startup, so this also catches a restart's leftovers promptly.
 */
@Component
@RequiredArgsConstructor
public class ArchiveMountWatcher {

    /** How long an archive mount may sit unbrowsed before it is released — generous enough for a browse pause. */
    static final long IDLE_WINDOW_MS = 600_000;

    /** How often the sweep runs. Frequent enough that an idle mount is released within a few minutes of the window. */
    private static final long SWEEP_INTERVAL_MS = 120_000;

    /** How often orphan reconciliation runs. Slower than the sweep — it probes the fleet, so it stays cheap. */
    private static final long RECONCILE_INTERVAL_MS = 900_000;

    private final ForMountingArchives forMountingArchives;

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void releaseIdleMounts() {
        forMountingArchives.unmountIdle(IDLE_WINDOW_MS);
    }

    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS)
    public void reconcileOrphanMounts() {
        forMountingArchives.reconcileMounts();
    }
}
