package net.vaier.application.service;

import net.vaier.application.GetLanServerReachabilityUseCase.Reachability;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.domain.LanServer;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerReachabilityServiceTest {

    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock ForProbingTcp forProbingTcp;
    @Mock ForPublishingEvents forPublishingEvents;

    LanServerReachabilityService service;

    @BeforeEach
    void setUp() {
        service = new LanServerReachabilityService(getLanServersUseCase, forProbingTcp, forPublishingEvents);
        lenient().when(getLanServersUseCase.getAll()).thenReturn(List.of());
        lenient().when(forProbingTcp.probe(anyString(), anyInt(), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
    }

    @Test
    void getReachability_beforeAnyProbe_returnsUnknown() {
        assertThat(service.getReachability("printer")).isEqualTo(Reachability.UNKNOWN);
    }

    @Test
    void refreshAll_anyPortConnects_marksOk() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        service.refreshAll();

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_anyPortRefused_marksOk() {
        // RST means a TCP packet came back — host is on the network.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.REFUSED);

        service.refreshAll();

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_allPortsTimeOut_marksDown() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        // default mock returns UNREACHABLE for all calls.

        service.refreshAll();

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.DOWN);
    }

    @Test
    void refreshAll_probesDockerEnabledLanServersToo() {
        // Pingability is independent of the Docker scrape — both signals are reported and
        // the UI combines them to produce green / yellow / red.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("nas", "192.168.3.50", true, 2375)
        ));
        when(forProbingTcp.probe(eq("192.168.3.50"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        service.refreshAll();

        assertThat(service.getReachability("nas")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_stopsAtFirstHostResponse() {
        // Either CONNECTED or REFUSED is enough to confirm the host is alive — early-exit.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        service.refreshAll();

        verify(forProbingTcp).probe("192.168.3.20", 80, 1000);
        verify(forProbingTcp, org.mockito.Mockito.never()).probe(anyString(), eq(443), anyInt());
        verify(forProbingTcp, org.mockito.Mockito.never()).probe(anyString(), eq(22), anyInt());
    }

    @Test
    void refreshAll_publishesSseEventWhenReachabilityChanges() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));

        service.refreshAll();

        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_unchangedCache_doesNotPublish() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));

        service.refreshAll(); // first refresh: DOWN → publishes
        service.refreshAll(); // second refresh: still DOWN → no event

        verify(forPublishingEvents, times(1))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_evictsRemovedLanServersFromCache() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        service.refreshAll();
        assertThat(service.getReachability("printer")).isEqualTo(Reachability.DOWN);

        when(getLanServersUseCase.getAll()).thenReturn(List.of());
        service.refreshAll();

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.UNKNOWN);
    }

    private static LanServerView view(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        return new LanServerView(new LanServer(name, lanAddress, runsDocker, dockerPort), "relay");
    }
}
