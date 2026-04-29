package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineTypeTest {

    @Test
    void hasFiveValues() {
        assertThat(MachineType.values()).containsExactlyInAnyOrder(
            MachineType.MOBILE_CLIENT,
            MachineType.WINDOWS_CLIENT,
            MachineType.UBUNTU_SERVER,
            MachineType.WINDOWS_SERVER,
            MachineType.LAN_SERVER
        );
    }

    @Test
    void isVpnPeer_isTrueForFourWgBackedValues() {
        assertThat(MachineType.MOBILE_CLIENT.isVpnPeer()).isTrue();
        assertThat(MachineType.WINDOWS_CLIENT.isVpnPeer()).isTrue();
        assertThat(MachineType.UBUNTU_SERVER.isVpnPeer()).isTrue();
        assertThat(MachineType.WINDOWS_SERVER.isVpnPeer()).isTrue();
    }

    @Test
    void isVpnPeer_isFalseForLanServer() {
        assertThat(MachineType.LAN_SERVER.isVpnPeer()).isFalse();
    }

    @Test
    void isServerType_isTrueForUbuntuWindowsAndLanServer() {
        assertThat(MachineType.UBUNTU_SERVER.isServerType()).isTrue();
        assertThat(MachineType.WINDOWS_SERVER.isServerType()).isTrue();
        assertThat(MachineType.LAN_SERVER.isServerType()).isTrue();
    }

    @Test
    void isServerType_isFalseForClientTypes() {
        assertThat(MachineType.MOBILE_CLIENT.isServerType()).isFalse();
        assertThat(MachineType.WINDOWS_CLIENT.isServerType()).isFalse();
    }

    @Test
    void defaultAllowedIps_returnsVpnSubnetForServerTypes() {
        String vpnSubnet = "10.13.13.0/24";
        assertThat(MachineType.UBUNTU_SERVER.defaultAllowedIps(vpnSubnet)).isEqualTo(vpnSubnet);
        assertThat(MachineType.WINDOWS_SERVER.defaultAllowedIps(vpnSubnet)).isEqualTo(vpnSubnet);
        assertThat(MachineType.LAN_SERVER.defaultAllowedIps(vpnSubnet)).isEqualTo(vpnSubnet);
    }

    @Test
    void defaultAllowedIps_returnsZeroAllForClientTypes() {
        String vpnSubnet = "10.13.13.0/24";
        assertThat(MachineType.MOBILE_CLIENT.defaultAllowedIps(vpnSubnet)).isEqualTo("0.0.0.0/0");
        assertThat(MachineType.WINDOWS_CLIENT.defaultAllowedIps(vpnSubnet)).isEqualTo("0.0.0.0/0");
    }
}
