package net.vaier.adapter.driven;

import net.vaier.adapter.driven.VpnNetworkSetupAdapter.HostnameResolver;
import net.vaier.adapter.driven.VpnNetworkSetupAdapter.ProcessResult;
import net.vaier.adapter.driven.VpnNetworkSetupAdapter.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VpnNetworkSetupAdapterTest {

    private static final String CONTAINER = "wireguard";
    private static final String SUBNET = "10.13.13.0/24";
    private static final String RESOLVED_IP = "172.20.0.5";

    private HostnameResolver hostnameResolver;
    private ProcessRunner processRunner;
    private VpnNetworkSetupAdapter adapter;

    @BeforeEach
    void setUp() {
        hostnameResolver = mock(HostnameResolver.class);
        processRunner = mock(ProcessRunner.class);
        adapter = new VpnNetworkSetupAdapter(hostnameResolver, processRunner);
        ReflectionTestUtils.setField(adapter, "wireguardContainerName", CONTAINER);
        ReflectionTestUtils.setField(adapter, "vpnSubnet", SUBNET);
    }

    @Test
    void setupVpnRouting_invokesIpRouteAddWithResolvedIp() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList())).thenReturn(new ProcessResult(0, ""));

        adapter.setupVpnRouting();

        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(commandCaptor.capture());
        assertThat(commandCaptor.getValue())
            .containsExactly("ip", "route", "add", SUBNET, "via", RESOLVED_IP);
    }

    @Test
    void setupVpnRouting_handlesNonZeroExitCodeWithoutThrowing() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList())).thenReturn(new ProcessResult(2, "something went wrong"));

        adapter.setupVpnRouting();

        verify(processRunner).run(anyList());
    }

    @Test
    void setupVpnRouting_treatsFileExistsOutputAsAlreadyConfigured() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList()))
            .thenReturn(new ProcessResult(2, "RTNETLINK answers: File exists"));

        adapter.setupVpnRouting();

        verify(processRunner).run(anyList());
    }

    @Test
    void setupVpnRouting_swallowsMissingBinary() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList()))
            .thenThrow(new IOException("Cannot run program \"ip\": error=2, No such file or directory"));

        adapter.setupVpnRouting();

        verify(processRunner).run(anyList());
    }

    @Test
    void setupVpnRouting_swallowsPermissionDenied() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList()))
            .thenThrow(new IOException("Cannot run program \"ip\": error=13, Permission denied"));

        adapter.setupVpnRouting();

        verify(processRunner).run(anyList());
    }

    @Test
    void setupVpnRouting_swallowsHostnameResolutionFailure() throws Exception {
        when(hostnameResolver.resolve(CONTAINER))
            .thenThrow(new UnknownHostException(CONTAINER));

        adapter.setupVpnRouting();

        verifyNoInteractions(processRunner);
    }

    @Test
    void setupVpnRouting_restoresInterruptFlagOnInterruption() throws Exception {
        when(hostnameResolver.resolve(CONTAINER)).thenReturn(RESOLVED_IP);
        when(processRunner.run(anyList())).thenThrow(new InterruptedException("interrupted"));

        try {
            adapter.setupVpnRouting();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
