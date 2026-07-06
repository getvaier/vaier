package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VaierServerSshAddressAdapterTest {

    @Test
    void parsesDefaultGatewayFromProcNetRoute() {
        // Real /proc/net/route content: the default route has Destination 00000000 and a
        // little-endian hex Gateway. 010011AC -> 172.17.0.1 (the Docker bridge host gateway).
        String procNetRoute = """
            Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT
            eth0\t00000000\t010011AC\t0003\t0\t0\t0\t00000000\t0\t0\t0
            eth0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
            """;

        assertThat(VaierServerSshAddressAdapter.parseDefaultGateway(procNetRoute)).contains("172.17.0.1");
    }

    @Test
    void returnsEmptyWhenNoDefaultRoute() {
        String procNetRoute = """
            Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT
            eth0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0
            """;

        assertThat(VaierServerSshAddressAdapter.parseDefaultGateway(procNetRoute)).isEmpty();
    }

    @Test
    void envOverrideWins() {
        VaierServerSshAddressAdapter adapter = new VaierServerSshAddressAdapter("10.0.0.5");

        assertThat(adapter.resolve()).isEqualTo("10.0.0.5");
    }

    @Test
    void parsesAnotherGateway() {
        // 0100000A -> 10.0.0.1
        String line = "eth0\t00000000\t0100000A\t0003\t0\t0\t0\t00000000\t0\t0\t0";
        assertThat(VaierServerSshAddressAdapter.parseDefaultGateway(line)).contains("10.0.0.1");
    }

    // Sanity: the helper is the tested seam; the adapter's env/gateway wiring is exercised above.
    @Test
    void parseHandlesEmpty() {
        assertThat(VaierServerSshAddressAdapter.parseDefaultGateway("")).isEqualTo(Optional.empty());
    }
}
