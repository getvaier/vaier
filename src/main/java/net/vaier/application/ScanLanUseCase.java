package net.vaier.application;

/**
 * Triggers an on-demand LAN scan (issue #246). The scan is intrusive and slow (a per-relay LAN
 * sweep), so it runs only when the operator asks and executes <em>asynchronously</em>: the call
 * returns immediately and the results land in the snapshot read via
 * {@link GetDiscoveredLanMachinesUseCase}. An Enterprise-only capability.
 */
public interface ScanLanUseCase {

    /** Kicks off a background scan of every scannable LAN. A no-op if a scan is already running. */
    void startScan();
}
