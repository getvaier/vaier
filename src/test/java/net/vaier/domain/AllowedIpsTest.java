package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedIpsTest {

    @Test
    void routeDelta_addsBroaderCidrFromNew() {
        var delta = AllowedIps.routeDelta("10.13.13.5/32", "10.13.13.5/32, 192.168.3.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.3.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void routeDelta_skipsPeer32HostRoutes_wgQuickOwnsThem() {
        // Peer /32 host routes are managed by wg-quick at bring-up — we must not add or remove them.
        var delta = AllowedIps.routeDelta("10.13.13.5/32", "10.13.13.7/32");

        assertThat(delta.toAdd()).isEmpty();
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void routeDelta_removesBroaderCidrThatDroppedFromNew() {
        var delta = AllowedIps.routeDelta(
            "10.13.13.5/32, 192.168.3.0/24, 10.0.0.0/16",
            "10.13.13.5/32, 192.168.3.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.3.0/24");
        assertThat(delta.toRemove()).containsExactly("10.0.0.0/16");
    }

    @Test
    void routeDelta_alwaysReEmitsExistingBroaderCidrSoDriftHeals() {
        // 192.168.3.0/24 was already present — must still appear in toAdd to heal any drift from
        // earlier wg set calls that bypassed the kernel routing table.
        var delta = AllowedIps.routeDelta(
            "10.13.13.5/32, 192.168.3.0/24",
            "10.13.13.5/32, 192.168.3.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.3.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void routeDelta_nullOldValueIsTreatedAsEmpty() {
        var delta = AllowedIps.routeDelta(null, "192.168.3.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.3.0/24");
        assertThat(delta.toRemove()).isEmpty();
    }

    @Test
    void routeDelta_deDuplicatesRepeatedCidrs() {
        var delta = AllowedIps.routeDelta(
            "192.168.3.0/24, 192.168.3.0/24",
            "192.168.3.0/24, 192.168.3.0/24");

        assertThat(delta.toAdd()).containsExactly("192.168.3.0/24");
    }

    @Test
    void parseCidrList_blankAndNull_returnsEmpty() {
        assertThat(AllowedIps.parseCidrList(null)).isEmpty();
        assertThat(AllowedIps.parseCidrList("")).isEmpty();
        assertThat(AllowedIps.parseCidrList("  ")).isEmpty();
    }

    @Test
    void parseCidrList_trimsAndPreservesOrder() {
        assertThat(AllowedIps.parseCidrList(" 10.0.0.0/8 , 192.168.1.0/24 "))
            .containsExactly("10.0.0.0/8", "192.168.1.0/24");
    }
}
