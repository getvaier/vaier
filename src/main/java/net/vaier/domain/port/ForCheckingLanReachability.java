package net.vaier.domain.port;

import net.vaier.domain.Reachability;

import java.util.Map;

public interface ForCheckingLanReachability {

    /**
     * Reachability for a specific LAN address. {@link Reachability#UNKNOWN} when no probe has
     * landed in the cache yet (either never seen, or still inside the debounce window).
     */
    Reachability getReachability(String lanAddress);

    /**
     * Epoch-second timestamp of the most recent successful probe for {@code lanAddress}, or
     * {@code null} if the host has never responded since startup. Preserved across subsequent
     * down probes — drives the "Last seen N ago" affordance on the UI.
     */
    Long getLastSeenEpochSec(String lanAddress);

    /**
     * Immutable snapshot of the full reachability map. The domain consults this in
     * {@link net.vaier.domain.ReverseProxyRoute#hostState} so a LAN service whose host is
     * confirmed DOWN reports {@code UNREACHABLE} and one we've never probed reports
     * {@code UNKNOWN} — instead of the misleading "OK because the relay is up" fallback
     * (issue #208). Addresses absent from the map are treated as {@link Reachability#UNKNOWN}.
     */
    Map<String, Reachability> snapshot();
}
