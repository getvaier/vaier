package net.vaier.adapter.driven;

import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForScanningLan.ScannedHost;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LanScanAdapterTest {

    /** A recording executor that returns canned sweep output regardless of the command. */
    private static ForExecutingInContainer executorReturning(String output) {
        return new ForExecutingInContainer() {
            public String execute(String containerName, String... command) { return output; }
            public String executeWithInput(String containerName, String input, String... command) { return output; }
            public void restartWithMasqueradeSidecar(String containerName) { }
        };
    }

    @Test
    void scanHost_returnsTheSingleHostItAnsweredFor() {
        LanScanAdapter adapter = new LanScanAdapter(
            executorReturning("192.168.3.50|2375,22|synology-nas\n"), "wireguard");

        Optional<ScannedHost> host = adapter.scanHost("192.168.3.50");

        assertThat(host).contains(new ScannedHost("192.168.3.50", List.of(2375, 22), "synology-nas"));
    }

    @Test
    void scanHost_returnsEmptyWhenNothingAnswered() {
        LanScanAdapter adapter = new LanScanAdapter(executorReturning(""), "wireguard");

        assertThat(adapter.scanHost("192.168.3.99")).isEmpty();
    }

    @Test
    void scanHost_rejectsANonIpv4Address() {
        LanScanAdapter adapter = new LanScanAdapter(executorReturning("anything"), "wireguard");

        assertThat(adapter.scanHost("not-an-ip")).isEmpty();
    }

    @Test
    void parsesPortsAndHostname() {
        List<ScannedHost> hosts = LanScanAdapter.parseScanOutput(
            "192.168.3.10|2375,22|docker01\n");

        assertThat(hosts).containsExactly(
            new ScannedHost("192.168.3.10", List.of(2375, 22), "docker01"));
    }

    @Test
    void treatsAnEmptyHostnameFieldAsNull() {
        List<ScannedHost> hosts = LanScanAdapter.parseScanOutput("192.168.3.50|5000|\n");

        assertThat(hosts).containsExactly(
            new ScannedHost("192.168.3.50", List.of(5000), null));
    }

    @Test
    void acceptsAPingOnlyHitWithNoOpenPorts() {
        List<ScannedHost> hosts = LanScanAdapter.parseScanOutput("192.168.3.99||pi5\n");

        assertThat(hosts).containsExactly(
            new ScannedHost("192.168.3.99", List.of(), "pi5"));
    }

    @Test
    void skipsBlankAndMalformedLines() {
        List<ScannedHost> hosts = LanScanAdapter.parseScanOutput(
            "\n  \nnot-a-host-line\n192.168.3.10|80|web\n|missing-ip|x\n");

        assertThat(hosts).extracting(ScannedHost::ipAddress).containsExactly("192.168.3.10");
    }

    @Test
    void handlesEmptyOutput() {
        assertThat(LanScanAdapter.parseScanOutput("")).isEmpty();
        assertThat(LanScanAdapter.parseScanOutput(null)).isEmpty();
    }
}
