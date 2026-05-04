package net.vaier.application.service;

import net.vaier.application.GetLanServerReachabilityUseCase.Reachability;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.NotifyAdminsOfPeerTransitionUseCase;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerReachabilityServiceTest {

    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock ForProbingTcp forProbingTcp;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock NotifyAdminsOfPeerTransitionUseCase notifier;

    LanServerReachabilityService service;

    /** Number of consecutive same-state probes required before the service commits a result
     *  (mirrors LanServerReachabilityService.REQUIRED_CONSECUTIVE_PROBES). */
    private static final int CONFIRM = 3;

    @BeforeEach
    void setUp() {
        service = new LanServerReachabilityService(getLanServersUseCase, forProbingTcp, forPublishingEvents, notifier);
        lenient().when(getLanServersUseCase.getAll()).thenReturn(List.of());
        lenient().when(forProbingTcp.probe(anyString(), anyInt(), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
    }

    private void refreshN(int n) {
        for (int i = 0; i < n; i++) service.refreshAll();
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

        refreshN(CONFIRM);

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

        refreshN(CONFIRM);

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_allPortsTimeOut_marksDown() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        // default mock returns UNREACHABLE for all calls.

        refreshN(CONFIRM);

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

        refreshN(CONFIRM);

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

        refreshN(CONFIRM);

        verify(forPublishingEvents).publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_unchangedCache_doesNotPublish() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));

        refreshN(CONFIRM);     // confirms DOWN → 1 publish
        service.refreshAll();  // still DOWN, already confirmed → no event

        verify(forPublishingEvents, times(1))
            .publish(eq("vpn-peers"), eq("lan-servers-updated"), anyString());
    }

    @Test
    void refreshAll_evictsRemovedLanServersFromCache() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        refreshN(CONFIRM);
        assertThat(service.getReachability("printer")).isEqualTo(Reachability.DOWN);

        when(getLanServersUseCase.getAll()).thenReturn(List.of());
        service.refreshAll();

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.UNKNOWN);
    }

    @Test
    void getLastSeenEpochSec_beforeAnyProbe_returnsNull() {
        assertThat(service.getLastSeenEpochSec("printer")).isNull();
    }

    @Test
    void refreshAll_okProbe_recordsLastSeenAtNow() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        long before = System.currentTimeMillis() / 1000;
        service.refreshAll();
        long after = System.currentTimeMillis() / 1000;

        assertThat(service.getLastSeenEpochSec("printer"))
            .isNotNull()
            .isBetween(before, after);
    }

    @Test
    void refreshAll_refusedProbe_recordsLastSeen() {
        // RST-back is enough — host is on the network, so it counts as "seen".
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.REFUSED);

        service.refreshAll();

        assertThat(service.getLastSeenEpochSec("printer")).isNotNull();
    }

    @Test
    void refreshAll_downProbe_keepsLastSeenNull() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        // default mock returns UNREACHABLE for all probe ports.

        service.refreshAll();

        assertThat(service.getLastSeenEpochSec("printer")).isNull();
    }

    @Test
    void refreshAll_downAfterOk_preservesLastSeen() {
        // "Last seen" means the last time the host responded — once recorded, a later DOWN
        // probe must not erase it (otherwise the UI would forget the host the moment it
        // goes offline, which is exactly when "last seen" is most useful).
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        refreshN(CONFIRM);
        Long firstSeen = service.getLastSeenEpochSec("printer");
        assertThat(firstSeen).isNotNull();

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
        refreshN(CONFIRM);

        assertThat(service.getReachability("printer")).isEqualTo(Reachability.DOWN);
        assertThat(service.getLastSeenEpochSec("printer")).isEqualTo(firstSeen);
    }

    @Test
    void refreshAll_evictsLastSeenForRemovedLanServers() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        service.refreshAll();
        assertThat(service.getLastSeenEpochSec("printer")).isNotNull();

        when(getLanServersUseCase.getAll()).thenReturn(List.of());
        service.refreshAll();

        assertThat(service.getLastSeenEpochSec("printer")).isNull();
    }

    @Test
    void refreshAll_firstObservation_doesNotNotify() {
        // First time we see a server, baseline its state silently — a Vaier restart must not
        // produce an email storm for every machine that's currently down.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));

        service.refreshAll();

        verify(notifier, never()).notifyAdmins(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshAll_okThenDown_notifiesAdminsOfDisconnect() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        refreshN(CONFIRM); // baseline OK, no notification

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
        refreshN(CONFIRM); // confirms DOWN → notify

        ArgumentCaptor<PeerSnapshot> captor = ArgumentCaptor.forClass(PeerSnapshot.class);
        verify(notifier).notifyAdmins(captor.capture());
        PeerSnapshot snap = captor.getValue();
        assertThat(snap.name()).isEqualTo("printer");
        assertThat(snap.peerType()).isEqualTo(MachineType.LAN_SERVER);
        assertThat(snap.connected()).isFalse();
        assertThat(snap.lanAddress()).isEqualTo("192.168.3.20");
    }

    @Test
    void refreshAll_downThenOk_notifiesAdminsOfReconnect() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        // default mock returns UNREACHABLE — confirm baseline DOWN.
        refreshN(CONFIRM);

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        refreshN(CONFIRM); // confirms OK → notify

        ArgumentCaptor<PeerSnapshot> captor = ArgumentCaptor.forClass(PeerSnapshot.class);
        verify(notifier).notifyAdmins(captor.capture());
        PeerSnapshot snap = captor.getValue();
        assertThat(snap.name()).isEqualTo("printer");
        assertThat(snap.peerType()).isEqualTo(MachineType.LAN_SERVER);
        assertThat(snap.connected()).isTrue();
    }

    @Test
    void refreshAll_unchangedReachability_doesNotNotify() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        refreshN(CONFIRM);     // baseline OK
        refreshN(CONFIRM);     // still OK, still OK, still OK

        verify(notifier, never()).notifyAdmins(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refreshAll_singleTransientFlip_doesNotNotify() {
        // After a confirmed OK baseline, a single DOWN probe (network blip / port edge case)
        // must never be enough to fire an email — that's the whole point of the debounce.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        refreshN(CONFIRM); // baseline OK

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
        service.refreshAll(); // single DOWN — pending, not committed

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        service.refreshAll(); // back to OK before threshold hit

        verify(notifier, never()).notifyAdmins(org.mockito.ArgumentMatchers.any());
        assertThat(service.getReachability("printer")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_warmupKeepsCacheUnknownUntilConfirmed() {
        // First couple of probes after Vaier startup must not flip the published cache —
        // otherwise the UI shows red briefly during the WireGuard tunnel warmup window.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);

        for (int i = 0; i < CONFIRM - 1; i++) {
            service.refreshAll();
            assertThat(service.getReachability("printer"))
                .as("must stay UNKNOWN until %d consecutive probes confirm", CONFIRM)
                .isEqualTo(Reachability.UNKNOWN);
        }
        service.refreshAll();
        assertThat(service.getReachability("printer")).isEqualTo(Reachability.OK);
    }

    @Test
    void refreshAll_swallowsNotifierExceptionsSoSchedulerKeepsRunning() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            view("printer", "192.168.3.20", false, null)
        ));
        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.CONNECTED);
        refreshN(CONFIRM); // baseline OK

        when(forProbingTcp.probe(eq("192.168.3.20"), eq(80), anyInt()))
            .thenReturn(ProbeResult.UNREACHABLE);
        org.mockito.Mockito.doThrow(new RuntimeException("notifier blew up"))
            .when(notifier).notifyAdmins(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatCode(() -> refreshN(CONFIRM))
            .doesNotThrowAnyException();
    }

    private static LanServerView view(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        return new LanServerView(new LanServer(name, lanAddress, runsDocker, dockerPort), "relay");
    }
}
