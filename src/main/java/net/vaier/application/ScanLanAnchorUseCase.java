package net.vaier.application;

/**
 * Triggers an on-demand scan of a <em>single</em> LAN (issue #246), identified by its anchor key —
 * a relay peer's id, or {@code LanAnchor.VAIER_SERVER_NAME} for the Vaier server's own LAN. The
 * operator picks which LAN to scan first, so the sweep touches only that one CIDR and settles fast,
 * rather than fanning out over every relay LAN at once ({@link ScanLanUseCase}). Runs
 * asynchronously: the call returns immediately and the results land in the snapshot read via
 * {@link GetDiscoveredLanMachinesUseCase}. An Enterprise-only capability.
 */
public interface ScanLanAnchorUseCase {

    /**
     * Kicks off a background scan of just the LAN identified by {@code anchorKey}, replacing that
     * LAN's slice of the snapshot while leaving every other LAN's results untouched. A no-op if a
     * scan is already running.
     *
     * @throws net.vaier.domain.NotFoundException when no scannable LAN carries {@code anchorKey}
     */
    void startScan(String anchorKey);
}
