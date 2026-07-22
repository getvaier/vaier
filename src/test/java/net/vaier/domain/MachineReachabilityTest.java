package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MachineReachabilityTest {

    @Test
    void vaierServerIsAlwaysReachable() {
        Machine server = Machine.vaierServer(null);
        assertThat(server.isReachable(Map.of())).isTrue();
    }

    @Test
    void lanServerReachableWhenCachedOk() {
        Machine nas = new Machine("nas", MachineType.LAN_SERVER, null, null, null, null, null, null,
            null, null, "192.168.3.50", true, 2375, DeviceCategory.NAS, null);
        assertThat(nas.isReachable(Map.of("192.168.3.50", Reachability.OK))).isTrue();
        assertThat(nas.isReachable(Map.of("192.168.3.50", Reachability.DOWN))).isFalse();
        assertThat(nas.isReachable(Map.of())).isFalse();
    }

    @Test
    void peerReachableWhenHandshakeFresh() {
        String fresh = String.valueOf(System.currentTimeMillis() / 1000);
        Machine connected = new Machine("alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            "1.2.3.4", "51820", fresh, "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        assertThat(connected.isReachable(Map.of())).isTrue();

        Machine stale = new Machine("bob", MachineType.UBUNTU_SERVER, "pk", "10.13.13.3/32",
            "1.2.3.4", "51820", "1000", "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        assertThat(stale.isReachable(Map.of())).isFalse();
    }
}
