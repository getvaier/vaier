package net.vaier.adapter.driven;

import net.vaier.adapter.driven.LanRouteAdapter.HostnameResolver;
import net.vaier.adapter.driven.LanRouteAdapter.ProcessResult;
import net.vaier.adapter.driven.LanRouteAdapter.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LanRouteAdapterTest {

    private static final String CONTAINER = "wireguard";
    private static final String WG_IP = "172.20.0.2";

    private HostnameResolver hostnameResolver;
    private ProcessRunner processRunner;
    private LanRouteAdapter adapter;

    @BeforeEach
    void setUp() {
        hostnameResolver = mock(HostnameResolver.class);
        processRunner = mock(ProcessRunner.class);
        adapter = new LanRouteAdapter(hostnameResolver, processRunner);
        ReflectionTestUtils.setField(adapter, "wireguardContainerName", CONTAINER);
        ReflectionTestUtils.setField(adapter, "vpnSubnet", "10.13.13.0/24");
    }

    @Test
    void syncLanRoutes_replacesEachDesiredCidrViaWireguardIp() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(WG_IP);
        when(processRunner.run(List.of("ip", "route", "show"))).thenReturn(new ProcessResult(0, ""));
        when(processRunner.run(List.of("ip", "route", "replace", "192.168.3.0/24", "via", WG_IP)))
            .thenReturn(new ProcessResult(0, ""));

        adapter.syncLanRoutes(Set.of("192.168.3.0/24"));

        verify(processRunner).run(List.of("ip", "route", "replace", "192.168.3.0/24", "via", WG_IP));
    }

    @Test
    void syncLanRoutes_removesStaleRoutesNoLongerDesired() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(WG_IP);
        String routeShow = """
            default via 172.20.0.1 dev eth0
            10.13.13.0/24 via 172.20.0.2 dev eth0
            172.20.0.0/16 dev eth0 proto kernel scope link src 172.20.0.6
            192.168.3.0/24 via 172.20.0.2 dev eth0
            192.168.99.0/24 via 172.20.0.2 dev eth0
            """;
        // Same `anyList()` stub returns ProcessResult(0, "") by default for replace/del; show overrides it.
        when(processRunner.run(anyList())).thenReturn(new ProcessResult(0, ""));
        when(processRunner.run(List.of("ip", "route", "show"))).thenReturn(new ProcessResult(0, routeShow));

        adapter.syncLanRoutes(Set.of("192.168.3.0/24"));

        verify(processRunner).run(List.of("ip", "route", "del", "192.168.99.0/24"));
        verify(processRunner, never()).run(List.of("ip", "route", "del", "192.168.3.0/24"));
        // VPN subnet route is owned by VpnNetworkSetupAdapter and must never be touched here.
        verify(processRunner, never()).run(List.of("ip", "route", "del", "10.13.13.0/24"));
    }

    @Test
    void syncLanRoutes_doesNotDeleteRoutesViaOtherGateways() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(WG_IP);
        String routeShow = """
            default via 172.20.0.1 dev eth0
            172.20.0.0/16 dev eth0 proto kernel scope link src 172.20.0.6
            192.168.7.0/24 via 10.0.0.99 dev eth0
            """;
        when(processRunner.run(List.of("ip", "route", "show"))).thenReturn(new ProcessResult(0, routeShow));
        when(processRunner.run(anyList())).thenReturn(new ProcessResult(0, ""));
        when(processRunner.run(List.of("ip", "route", "show"))).thenReturn(new ProcessResult(0, routeShow));

        adapter.syncLanRoutes(Set.of());

        verify(processRunner, never()).run(List.of("ip", "route", "del", "192.168.7.0/24"));
        verify(processRunner, never()).run(List.of("ip", "route", "del", "172.20.0.0/16"));
    }

    @Test
    void syncLanRoutes_emptyDesired_andNoExistingViaWg_isNoop() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(WG_IP);
        when(processRunner.run(List.of("ip", "route", "show")))
            .thenReturn(new ProcessResult(0, "default via 172.20.0.1 dev eth0\n"));

        adapter.syncLanRoutes(Set.of());

        // Only the route-show call should have happened.
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(captor.capture());
        assertThat(captor.getValue()).containsExactly("ip", "route", "show");
    }

    @Test
    void syncLanRoutes_skipsWhenWireguardCannotResolve() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenThrow(new UnknownHostException(CONTAINER));

        adapter.syncLanRoutes(Set.of("192.168.3.0/24"));

        verifyNoInteractions(processRunner);
    }

    @Test
    void syncLanRoutes_swallowsIoErrors() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(WG_IP);
        when(processRunner.run(anyList())).thenThrow(new IOException("ip: command not found"));

        adapter.syncLanRoutes(Set.of("192.168.3.0/24"));
    }
}
