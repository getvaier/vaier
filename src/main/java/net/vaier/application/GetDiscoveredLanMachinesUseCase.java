package net.vaier.application;

import net.vaier.domain.DiscoveredLanMachine;

import java.time.Instant;
import java.util.List;

/**
 * Reads the latest LAN-scan snapshot (issue #246): whether a scan is currently running, the
 * machines found by the most recent completed scan, and when that scan finished. The scan itself is
 * kicked off via {@link ScanLanUseCase}; this query never blocks on a sweep. An Enterprise-only
 * capability.
 */
public interface GetDiscoveredLanMachinesUseCase {

    LanScanSnapshot snapshot();

    /**
     * The snapshot scoped to a single LAN: same status and last-completed time, but only the hosts
     * discovered on the LAN identified by {@code anchorKey}. Lets the picker show one LAN's
     * candidates without the browser re-deriving membership.
     */
    LanScanSnapshot snapshot(String anchorKey);

    /** Whether a scan is in flight. */
    enum ScanStatus { IDLE, SCANNING }

    /**
     * @param status            {@code SCANNING} while a sweep is running, otherwise {@code IDLE}
     * @param machines          the not-yet-registered hosts found by the most recent completed scan
     * @param lastScanCompleted when the most recent scan finished, or {@code null} if none has yet
     */
    record LanScanSnapshot(ScanStatus status, List<DiscoveredLanMachine> machines,
                           Instant lastScanCompleted) {}
}
