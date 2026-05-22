package net.vaier.rest;

import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.RefreshContainerStateUseCase;
import net.vaier.application.RefreshLaunchpadVersionsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StateRefreshSchedulerTest {

    RefreshContainerStateUseCase containerState;
    GetLanServerScrapeUseCase lanServerScrape;
    GetLanServerReachabilityUseCase lanServerReachability;
    RefreshLaunchpadVersionsUseCase launchpadVersions;
    StateRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        containerState = mock(RefreshContainerStateUseCase.class);
        lanServerScrape = mock(GetLanServerScrapeUseCase.class);
        lanServerReachability = mock(GetLanServerReachabilityUseCase.class);
        launchpadVersions = mock(RefreshLaunchpadVersionsUseCase.class);
        scheduler = new StateRefreshScheduler(containerState, lanServerScrape,
            lanServerReachability, launchpadVersions);
    }

    @Test
    void refresh_refreshesEveryStateSource() {
        scheduler.refresh();

        verify(containerState).refresh();
        verify(lanServerScrape).refreshAll();
        verify(lanServerReachability).refreshAll();
        verify(launchpadVersions).refreshLaunchpadVersions();
    }

    @Test
    void refresh_oneFailingStep_stillRunsTheRest() {
        doThrow(new RuntimeException("peer scrape boom")).when(containerState).refresh();

        scheduler.refresh();

        verify(lanServerScrape).refreshAll();
        verify(lanServerReachability).refreshAll();
        verify(launchpadVersions).refreshLaunchpadVersions();
    }
}
