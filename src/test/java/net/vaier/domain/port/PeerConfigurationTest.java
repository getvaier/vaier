package net.vaier.domain.port;

import net.vaier.domain.ConflictException;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // --- is this machine one of our peers? ---

    @Test
    void isPeerMachine_findsAPeerByItsMachineId() {
        PeerConfiguration phone = peerFor("phone");
        List<PeerConfiguration> peers = List.of(phone, peerFor("laptop"));

        assertThat(PeerConfiguration.isPeerMachine(peers, phone.machineId())).isTrue();
    }

    @Test
    void isPeerMachine_isFalseForAMachineNoPeerIs() {
        List<PeerConfiguration> peers = List.of(peerFor("phone"));

        assertThat(PeerConfiguration.isPeerMachine(peers, TestMachineIds.of("ghost"))).isFalse();
        assertThat(PeerConfiguration.isPeerMachine(List.of(), TestMachineIds.of("phone"))).isFalse();
    }

    /** Never a match by accident: no machine id names no machine. */
    @Test
    void isPeerMachine_isFalseForNoMachineIdAtAll() {
        assertThat(PeerConfiguration.isPeerMachine(List.of(peerFor("phone")), null)).isFalse();
    }

    // --- removal from the machine's own tunnel ---

    @Test
    void removableBy_isFalseOnlyForAnAppDeviceRemovingItselfThroughItsOwnTunnel_whichIsRefused() {
        PeerConfiguration appDevice = new PeerConfiguration("laptop", "Laptop", "10.13.13.8", "",
            MachineType.WINDOWS_CLIENT, null, null, null, null, null, TestMachineIds.of("laptop"), "DEVICE_KEY");
        PeerConfiguration wireGuardAppDevice = peerFor("phone");

        assertThat(appDevice.removableBy(Optional.of(appDevice.machineId()))).isFalse();
        assertThatThrownBy(() -> appDevice.refuseRemovalBy(Optional.of(appDevice.machineId())))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Laptop").hasMessageContaining("Leave Vaier");

        record Row(String label, PeerConfiguration peer, Optional<MachineId> caller) {}
        for (Row row : new Row[] {
            new Row("app device, removed from another machine", appDevice, Optional.of(TestMachineIds.of("other"))),
            new Row("app device, removed from off the tunnel", appDevice, Optional.empty()),
            // The page already warned this browser it will lose Vaier; the removal itself still works.
            new Row("WireGuard-app peer removing itself", wireGuardAppDevice,
                Optional.of(wireGuardAppDevice.machineId())),
        }) {
            assertThat(row.peer().removableBy(row.caller())).as(row.label()).isTrue();
            assertThatCode(() -> row.peer().refuseRemovalBy(row.caller())).as(row.label()).doesNotThrowAnyException();
        }
    }

    private static PeerConfiguration peerFor(String name) {
        return new PeerConfiguration(name, name, "10.13.13.2", "", MachineType.UBUNTU_SERVER,
            null, null, null, null, null, TestMachineIds.of(name), null);
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
