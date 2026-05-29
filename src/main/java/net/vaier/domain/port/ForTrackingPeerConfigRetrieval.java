package net.vaier.domain.port;

/**
 * One-shot gate around the secret-bearing peer config endpoints (#202). The WireGuard private
 * key inside a peer config has no session, no revocation, and works for any number of devices
 * simultaneously — so a photo of the QR or a leaked screenshot outlives any login. Marking a
 * peer "viewed" the first time any of the five secret-bearing endpoints is hit causes every
 * subsequent attempt to 410, and the only way to get a fresh config is delete + recreate
 * (which rotates the keypair as a side effect).
 *
 * Implementations must persist the marker across process restarts (no in-memory state) and
 * must clean up when the peer's config directory is removed by the delete flow — placing the
 * marker inside the peer directory handles both naturally.
 */
public interface ForTrackingPeerConfigRetrieval {

    /**
     * Atomically marks {@code peerName} as viewed if it isn't already. Returns {@code true}
     * if this call burned the budget (first view, caller may serve the secret), {@code false}
     * if the marker was already set (caller must return 410 Gone).
     *
     * Throws if the peer directory doesn't exist — distinguishable so the caller can return
     * 404 instead of 410 for unknown peers.
     */
    boolean markViewedIfNotAlready(String peerName);

    /** True iff the marker is already set for {@code peerName}. Read-only — does not mark. */
    boolean isAlreadyViewed(String peerName);

    /**
     * Clears the viewed marker for {@code peerName}, re-opening the one-shot retrieval budget so
     * the config can be delivered once more. Used by a {@code Reissue} (which deliberately
     * re-exposes the preserved secret). A no-op when no marker is set.
     */
    void resetViewed(String peerName);
}
