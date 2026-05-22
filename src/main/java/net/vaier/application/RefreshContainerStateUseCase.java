package net.vaier.application;

/**
 * Refreshes the cached peer-container discovery snapshot.
 *
 * <p>Peer container discovery scrapes every server-peer's Docker daemon over the VPN — slow,
 * and slow-to-fail when a peer is unreachable. It must never run on a request thread; the
 * state-refresh scheduler is the single caller of {@link #refresh()}.
 */
public interface RefreshContainerStateUseCase {

    /** Re-scrape peer containers and replace the cached snapshot served by discovery reads. */
    void refresh();
}
