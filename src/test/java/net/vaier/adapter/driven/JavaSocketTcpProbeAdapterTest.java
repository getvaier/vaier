package net.vaier.adapter.driven;

import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSocketTcpProbeAdapterTest {

    private final JavaSocketTcpProbeAdapter adapter = new JavaSocketTcpProbeAdapter();

    @Test
    void probe_listeningPort_returnsConnected() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();

            ProbeResult result = adapter.probe("127.0.0.1", port, 500);

            assertThat(result).isEqualTo(ProbeResult.CONNECTED);
        }
    }

    @Test
    void probe_closedPortOnLiveHost_returnsRefused() {
        // 127.0.0.1 is always alive; an arbitrary high port is unlikely to be open.
        ProbeResult result = adapter.probe("127.0.0.1", 1, 500);

        assertThat(result).isEqualTo(ProbeResult.REFUSED);
    }

    @Test
    void probe_unreachableHost_returnsUnreachable() {
        // 192.0.2.0/24 is the TEST-NET-1 block reserved for documentation; never routable.
        ProbeResult result = adapter.probe("192.0.2.1", 80, 200);

        assertThat(result).isEqualTo(ProbeResult.UNREACHABLE);
    }
}
