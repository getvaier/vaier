package net.vaier.adapter.driven;

import net.vaier.domain.DiscoveredLanMachine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDiscoveredLanMachineStoreTest {

    private final InMemoryDiscoveredLanMachineStore store = new InMemoryDiscoveredLanMachineStore();

    private static DiscoveredLanMachine machine(String ip) {
        return new DiscoveredLanMachine(ip, "host-" + ip, List.of(2375), "apalveien5");
    }

    @Test
    void startsEmptyWithNoCompletionTime() {
        assertThat(store.current()).isEmpty();
        assertThat(store.lastScanCompleted()).isNull();
    }

    @Test
    void storeReplacesResultsAndStampsCompletion() {
        store.store(List.of(machine("192.168.3.10")));

        assertThat(store.current()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactly("192.168.3.10");
        assertThat(store.lastScanCompleted()).isNotNull();
    }

    @Test
    void findByIpAddressReturnsMatchOrEmpty() {
        store.store(List.of(machine("192.168.3.10"), machine("192.168.3.11")));

        assertThat(store.findByIpAddress("192.168.3.11"))
            .map(DiscoveredLanMachine::ipAddress).contains("192.168.3.11");
        assertThat(store.findByIpAddress("10.0.0.1")).isEmpty();
    }

    @Test
    void forgetDropsTheHostButLeavesTheCompletionTime() {
        store.store(List.of(machine("192.168.3.10"), machine("192.168.3.11")));
        var stampedAt = store.lastScanCompleted();

        store.forget("192.168.3.10");

        assertThat(store.current()).extracting(DiscoveredLanMachine::ipAddress)
            .containsExactly("192.168.3.11");
        assertThat(store.lastScanCompleted()).isEqualTo(stampedAt);
    }
}
