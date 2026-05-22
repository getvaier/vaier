package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.RefreshLaunchpadVersionsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The single place service/machine state is refreshed.
 *
 * <p>One scheduled tick re-scrapes every slow remote source — peer Docker daemons, LAN-server
 * Docker daemons, LAN-server reachability, launchpad version endpoints — into the caches that
 * REST endpoints read. No controller scrapes on a request thread, so the launchpad, services,
 * and machines pages stay fast no matter how slow or unreachable a remote host is.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StateRefreshScheduler {

    private final RefreshContainerStateUseCase containerState;
    private final GetLanServerScrapeUseCase lanServerScrape;
    private final GetLanServerReachabilityUseCase lanServerReachability;
    private final RefreshLaunchpadVersionsUseCase launchpadVersions;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void refresh() {
        refreshStep("peer container discovery", containerState::refresh);
        refreshStep("LAN server container scrape", lanServerScrape::refreshAll);
        refreshStep("LAN server reachability", lanServerReachability::refreshAll);
        refreshStep("launchpad version probes", launchpadVersions::refreshLaunchpadVersions);
    }

    /** Each source is refreshed in isolation so one failure doesn't starve the others. */
    private void refreshStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.debug("{} refresh failed: {}", what, e.getMessage());
        }
    }
}
