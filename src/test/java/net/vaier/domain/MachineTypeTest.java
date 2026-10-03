package net.vaier.domain;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineTypeTest {

    @Test
    void hasFourValues() {
        assertThat(MachineType.values()).containsExactlyInAnyOrder(
            MachineType.MOBILE_CLIENT,
            MachineType.WINDOWS_CLIENT,
            MachineType.UBUNTU_SERVER,
            MachineType.LAN_SERVER
        );
    }

    @Test
    void isVpnPeer_isTrueForThreeWgBackedValues() {
        assertThat(MachineType.MOBILE_CLIENT.isVpnPeer()).isTrue();
        assertThat(MachineType.WINDOWS_CLIENT.isVpnPeer()).isTrue();
        assertThat(MachineType.UBUNTU_SERVER.isVpnPeer()).isTrue();
    }

    @Test
    void isVpnPeer_isFalseForLanServer() {
        assertThat(MachineType.LAN_SERVER.isVpnPeer()).isFalse();
    }

    @Test
    void isServerType_isTrueForUbuntuAndLanServer() {
        assertThat(MachineType.UBUNTU_SERVER.isServerType()).isTrue();
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
        assertThat(MachineType.LAN_SERVER.defaultAllowedIps(vpnSubnet)).isEqualTo(vpnSubnet);
    }

    @Test
    void defaultAllowedIps_returnsZeroAllForClientTypes() {
        String vpnSubnet = "10.13.13.0/24";
        assertThat(MachineType.MOBILE_CLIENT.defaultAllowedIps(vpnSubnet)).isEqualTo("0.0.0.0/0");
        assertThat(MachineType.WINDOWS_CLIENT.defaultAllowedIps(vpnSubnet)).isEqualTo("0.0.0.0/0");
    }

    // --- personal devices join through the Vaier app ---

    @Test
    void onlyPersonalDevices_joinThroughTheVaierApp_andVaierRefusesToMintThemAConfig() {
        record Row(MachineType type, boolean joinsThroughVaierApp) {}
        for (Row row : List.of(new Row(MachineType.MOBILE_CLIENT, true),
                               new Row(MachineType.WINDOWS_CLIENT, true),
                               new Row(MachineType.UBUNTU_SERVER, false),
                               new Row(MachineType.LAN_SERVER, false))) {
            assertThat(row.type().joinsThroughVaierApp()).as(row.type().name()).isEqualTo(row.joinsThroughVaierApp());
            if (row.joinsThroughVaierApp()) {
                assertThatThrownBy(() -> row.type().requireVaierMintedConfig("Geir's phone"))
                    .as(row.type().name())
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Geir's phone")
                    .hasMessageContaining("Install the Vaier app from Your services");
            } else {
                assertThatCode(() -> row.type().requireVaierMintedConfig("nas")).doesNotThrowAnyException();
            }
        }
    }

    // --- defaultType (#220) ---

    @Test
    void defaultType_isUbuntuServer() {
        assertThat(MachineType.defaultType()).isEqualTo(MachineType.UBUNTU_SERVER);
    }
}
