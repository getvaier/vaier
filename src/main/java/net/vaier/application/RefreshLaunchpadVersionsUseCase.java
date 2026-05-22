package net.vaier.application;

/**
 * Refreshes the cached map of launchpad service versions.
 *
 * <p>Each version is an HTTP GET to a possibly-slow LAN host (issue #210). Probing must never
 * run on a request thread; the state-refresh scheduler is the single caller.
 */
public interface RefreshLaunchpadVersionsUseCase {

    /** Re-probe every route with a configured version endpoint and replace the cached map. */
    void refreshLaunchpadVersions();
}
