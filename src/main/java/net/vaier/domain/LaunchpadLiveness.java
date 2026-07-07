package net.vaier.domain;

/**
 * The reachability-presentation state of a launchpad tile's status dot. Distinct from
 * {@link LaunchpadVisibility} (which governs whether the tile links and dims): the dot reports
 * only what we know about the host's reachability. The domain owns the derivation rule (see
 * {@link ReverseProxyRoute#launchpadLiveness}); the launchpad client only renders the result.
 */
public enum LaunchpadLiveness {
    /** Host confirmed reachable — green dot. */
    LIVE,
    /** Reachability not yet probed — grey dot. */
    PENDING,
    /** Host confirmed unreachable — red dot. */
    OFFLINE
}
