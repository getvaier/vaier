package net.vaier.application.service;

import net.vaier.domain.port.ForDiscoveringLanServerContainers;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerScrapeServiceTest {

    /** Mirrors LanServerScrapeService.REQUIRED_CONSECUTIVE_SCRAPES — the dampening window. */
    private static final int CONFIRM = 3;

    @Mock ForDiscoveringLanServerContainers discoverer;
    @Mock ForPublishingEvents forPublishingEvents;

    LanServerScrapeService service;

    @BeforeEach
    void setUp() {
        service = new LanServerScrapeService(discoverer, forPublishingEvents);
    }

    @Test
    void getLanServerContainers_beforeAnyRefresh_returnsEmpty() {
        assertThat(service.getLanServerContainers()).isEmpty();
    }

    @Test
    void refreshAll_firstObservation_commitsImmediatelyAndPublishes() {
        // No 90s warmup blackout — the very first scrape lands in the cache so the page isn't
        // empty for a minute and a half after Vaier boots.
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(
            ok("nas")
        ));

        service.refreshAll();

        assertThat(service.getLanServerContainers()).extracting(LanServerContainers::name)
            .containsExactly("nas");
        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_unchangedStatus_doesNotPublishAgain() {
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // first observation publishes

        service.refreshAll(); // same status — no event
        service.refreshAll();

        verify(forPublishingEvents, times(1))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_singleTransientFlip_doesNotPublishAndKeepsConfirmedStatus() {
        // The whole point of the debounce: a one-cycle Docker socket blip must not flip the
        // green/yellow indicator. UI stays green for the user.
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // baseline OK

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(unreachable("nas")));
        service.refreshAll(); // single UNREACHABLE — pending, not committed

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // back to OK before threshold hit

        // Only the initial baseline-publish should have fired.
        verify(forPublishingEvents, times(1))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
        assertThat(service.getLanServerContainers()).extracting(LanServerContainers::status)
            .containsExactly("OK");
    }

    @Test
    void refreshAll_threeConsecutiveOpposite_commitsAndPublishes() {
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // baseline OK (publishes once)

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(unreachable("nas")));
        for (int i = 0; i < CONFIRM; i++) service.refreshAll();

        verify(forPublishingEvents, times(2))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
        assertThat(service.getLanServerContainers()).extracting(LanServerContainers::status)
            .containsExactly("UNREACHABLE");
    }

    @Test
    void refreshAll_evictsRemovedServersFromCache() {
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll();
        assertThat(service.getLanServerContainers()).hasSize(1);

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of());
        service.refreshAll();

        assertThat(service.getLanServerContainers()).isEmpty();
    }

    @Test
    void refreshAll_evictionTriggersPublishOnce() {
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // first publish

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of());
        service.refreshAll(); // cache changes (eviction) → second publish

        verify(forPublishingEvents, times(2))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_unchangedStatus_refreshesContainerList() {
        // Status stays OK but container list grows — the cached entry should reflect the
        // latest scrape so the next /docker-services/lan-servers fetch sees the new container.
        LanServerContainers initial = new LanServerContainers(
            "nas", "192.168.3.50", 2375, "relay", "OK", List.of());
        LanServerContainers updated = new LanServerContainers(
            "nas", "192.168.3.50", 2375, "relay", "OK", List.of(/* one extra container */));

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(initial));
        service.refreshAll();

        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(updated));
        service.refreshAll();

        assertThat(service.getLanServerContainers().get(0)).isSameAs(updated);
    }

    @Test
    void refreshAll_pendingFlipResetsWhenStatusReturnsToConfirmed() {
        // Pattern: OK (baseline) → UNREACHABLE (pending) → UNREACHABLE (pending) → OK
        // → UNREACHABLE again. The intermediate OK must reset the pending counter so we
        // don't accidentally commit on the very next UNREACHABLE.
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // baseline OK
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(unreachable("nas")));
        service.refreshAll(); // pending count = 1
        service.refreshAll(); // pending count = 2
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(ok("nas")));
        service.refreshAll(); // back to confirmed → reset
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of(unreachable("nas")));
        service.refreshAll(); // pending count = 1 again

        assertThat(service.getLanServerContainers()).extracting(LanServerContainers::status)
            .containsExactly("OK");
        verify(forPublishingEvents, times(1))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_swallowsNoServers_doesNotPublish() {
        when(discoverer.discoverAllLanServerContainers()).thenReturn(List.of());

        service.refreshAll();

        verify(forPublishingEvents, never()).publish(anyString(), anyString(), anyString());
    }

    private static LanServerContainers ok(String name) {
        return new LanServerContainers(name, "192.168.3.50", 2375, "relay", "OK", List.of());
    }

    private static LanServerContainers unreachable(String name) {
        return new LanServerContainers(name, "192.168.3.50", 2375, "relay", "UNREACHABLE", List.of());
    }
}
