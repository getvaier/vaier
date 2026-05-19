package net.vaier.domain;

/**
 * The launchpad-rendering state of a published service. The domain owns the derivation rule
 * (see {@link ReverseProxyRoute#launchpadVisibility}); the launchpad client only cares about
 * the resulting value.
 */
public enum LaunchpadVisibility {
    NOT_VISIBLE,
    VISIBLE_INACTIVE,
    VISIBLE_ACTIVE
}
