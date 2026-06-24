package net.vaier.domain.port;

import net.vaier.domain.DeviceCategory;
import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PeerConfigurationTest {

    private static PeerConfiguration peer(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void lanCidrOwner_findsThePeerThatAlreadyOwnsTheCidr() {
        List<PeerConfiguration> peers = List.of(
            peer("alice", "192.168.1.0/24"),
            peer("bob", "192.168.2.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.2.0/24", "alice"))
            .map(PeerConfiguration::id)
            .contains("bob");
    }

    @Test
    void lanCidrOwner_ignoresThePeerBeingExcluded() {
        List<PeerConfiguration> peers = List.of(peer("alice", "192.168.1.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.1.0/24", "alice")).isEmpty();
    }

    @Test
    void lanCidrOwner_emptyWhenNoPeerOwnsTheCidr() {
        List<PeerConfiguration> peers = List.of(peer("alice", "192.168.1.0/24"));

        assertThat(PeerConfiguration.lanCidrOwner(peers, "192.168.9.0/24", "bob")).isEmpty();
        assertThat(PeerConfiguration.lanCidrOwner(peers, null, "bob")).isEmpty();
    }

    // --- device category (override + effective) ---

    @Test
    void eightArgConstructor_defaultsDeviceCategoryOverrideToNull() {
        assertThat(peer("alice", null).deviceCategory()).isNull();
    }

    @Test
    void effectiveDeviceCategory_detectsFromNameThenType() {
        // Name has no keyword; UBUNTU_SERVER -> SERVER.
        PeerConfiguration server = new PeerConfiguration("box-1", "box-1", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null);
        assertThat(server.effectiveDeviceCategory()).isEqualTo(DeviceCategory.SERVER);
        assertThat(server.deviceCategoryOverridden()).isFalse();

        // Name keyword "synology" wins over the SERVER type.
        PeerConfiguration nas = new PeerConfiguration("synology", "synology", "10.13.13.3", "",
            MachineType.UBUNTU_SERVER, null, null, null, null);
        assertThat(nas.effectiveDeviceCategory()).isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void effectiveDeviceCategory_overrideWins() {
        PeerConfiguration peer = new PeerConfiguration("synology", "synology", "10.13.13.3", "",
            MachineType.UBUNTU_SERVER, null, null, null, DeviceCategory.PRINTER);
        assertThat(peer.effectiveDeviceCategory()).isEqualTo(DeviceCategory.PRINTER);
        assertThat(peer.deviceCategoryOverridden()).isTrue();
    }

    @Test
    void effectiveDeviceCategory_usesDisplayNameForDetection() {
        // Detection runs on the operator-facing display name (so a rename re-detects).
        PeerConfiguration peer = new PeerConfiguration("box-1", "my-iphone", "10.13.13.4", "",
            MachineType.UBUNTU_SERVER, null, null, null, null);
        assertThat(peer.effectiveDeviceCategory()).isEqualTo(DeviceCategory.PHONE);
    }
}
