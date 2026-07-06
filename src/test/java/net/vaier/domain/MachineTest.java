package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MachineTest {

    @Test
    void fromPeer_withConnectedClient_carriesRuntimeState() {
        PeerConfiguration peer = new PeerConfiguration("nas", "NAS", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.2", null);
        VpnClient client = new VpnClient("pk", "10.13.13.2/32", "1.2.3.4", "51820", "1700000000", "10", "20");

        Machine machine = Machine.fromPeer(peer, client);

        assertThat(machine.name()).isEqualTo("NAS");
        assertThat(machine.type()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(machine.publicKey()).isEqualTo("pk");
        assertThat(machine.endpointIp()).isEqualTo("1.2.3.4");
        assertThat(machine.lanCidr()).isEqualTo("192.168.1.0/24");
        assertThat(machine.lanAddress()).isEqualTo("192.168.1.2");
        assertThat(machine.runsDocker()).isTrue();
        assertThat(machine.dockerPort()).isNull();
    }

    @Test
    void fromPeer_withoutClient_leavesRuntimeStateNull() {
        PeerConfiguration peer = new PeerConfiguration("laptop", "Laptop", "10.13.13.4", "",
            MachineType.WINDOWS_CLIENT, null, null, null);

        Machine machine = Machine.fromPeer(peer, null);

        assertThat(machine.name()).isEqualTo("Laptop");
        assertThat(machine.publicKey()).isNull();
        assertThat(machine.endpointIp()).isNull();
        assertThat(machine.latestHandshake()).isNull();
        assertThat(machine.runsDocker()).isFalse();
    }

    @Test
    void fromLanServer_buildsLanServerMachine() {
        LanServer server = new LanServer("vpc-box", "172.31.5.20", true, 2375);

        Machine machine = Machine.fromLanServer(server, "172.31.0.0/16");

        assertThat(machine.name()).isEqualTo("vpc-box");
        assertThat(machine.type()).isEqualTo(MachineType.LAN_SERVER);
        assertThat(machine.publicKey()).isNull();
        assertThat(machine.lanCidr()).isEqualTo("172.31.0.0/16");
        assertThat(machine.lanAddress()).isEqualTo("172.31.5.20");
        assertThat(machine.runsDocker()).isTrue();
        assertThat(machine.dockerPort()).isEqualTo(2375);
    }

    // --- device category (effective) ---

    @Test
    void fromPeer_carriesEffectiveDeviceCategoryFromTheConfig() {
        // No override; name "Laptop" detects to LAPTOP for a WINDOWS_CLIENT.
        PeerConfiguration peer = new PeerConfiguration("laptop", "Laptop", "10.13.13.4", "",
            MachineType.WINDOWS_CLIENT, null, null, null);

        assertThat(Machine.fromPeer(peer, null).deviceCategory()).isEqualTo(DeviceCategory.LAPTOP);
    }

    @Test
    void fromPeer_overrideWins() {
        PeerConfiguration peer = new PeerConfiguration("laptop", "Laptop", "10.13.13.4", "",
            MachineType.WINDOWS_CLIENT, null, null, null, DeviceCategory.SERVER);

        assertThat(Machine.fromPeer(peer, null).deviceCategory()).isEqualTo(DeviceCategory.SERVER);
    }

    @Test
    void fromLanServer_carriesEffectiveDeviceCategory() {
        LanServer server = new LanServer("my-synology", "172.31.5.20", false, null);

        assertThat(Machine.fromLanServer(server, "172.31.0.0/16").deviceCategory())
            .isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void fromLanServer_overrideWins() {
        LanServer server = new LanServer("box", "172.31.5.20", false, null, null, DeviceCategory.PRINTER);

        assertThat(Machine.fromLanServer(server, "172.31.0.0/16").deviceCategory())
            .isEqualTo(DeviceCategory.PRINTER);
    }

    // --- nameIsTaken: machine names are unique across Vaier (#284) ---

    @Test
    void nameIsTaken_trueWhenAnotherMachineHasTheSameName() {
        assertThat(Machine.nameIsTaken("nas", List.of("router", "nas", "printer"))).isTrue();
    }

    @Test
    void nameIsTaken_falseWhenNameIsFree() {
        assertThat(Machine.nameIsTaken("media-nas", List.of("router", "nas"))).isFalse();
    }

    @Test
    void nameIsTaken_isCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        assertThat(Machine.nameIsTaken("  NAS ", List.of("nas"))).isTrue();
    }

    @Test
    void nameIsTaken_nullOrBlankCandidateNeverCollides() {
        assertThat(Machine.nameIsTaken(null, List.of("nas"))).isFalse();
        assertThat(Machine.nameIsTaken("   ", List.of("nas"))).isFalse();
    }

    @Test
    void nameIsTaken_ignoresNullAndBlankExistingNames() {
        assertThat(Machine.nameIsTaken("nas", java.util.Arrays.asList(null, "", "  "))).isFalse();
    }

    // --- SSH-access default derivation (#307) ---

    @Test
    void defaultSshAccess_serverTypeWithServerCategory_true() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.SERVER, MachineType.UBUNTU_SERVER)).isTrue();
    }

    @Test
    void defaultSshAccess_nas_true() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.NAS, MachineType.LAN_SERVER)).isTrue();
    }

    @Test
    void defaultSshAccess_lanServerPrinter_false_applianceVetoesServerType() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.PRINTER, MachineType.LAN_SERVER)).isFalse();
    }

    @Test
    void defaultSshAccess_mobileClientPhone_false() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.PHONE, MachineType.MOBILE_CLIENT)).isFalse();
    }

    @Test
    void defaultSshAccess_serverTypeWithGenericCategory_true_serverFallback() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.GENERIC, MachineType.UBUNTU_SERVER)).isTrue();
    }

    @Test
    void defaultSshAccess_genericClient_false() {
        assertThat(Machine.defaultSshAccess(DeviceCategory.GENERIC, MachineType.MOBILE_CLIENT)).isFalse();
    }

    @Test
    void effectiveSshAccess_overrideWinsOverDefault() {
        // A server that would default to true, pinned off.
        PeerConfiguration peer = new PeerConfiguration("srv", "srv", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null, false);
        Machine machine = Machine.fromPeer(peer, null);
        assertThat(machine.effectiveSshAccess()).isFalse();

        // A printer that would default to false, pinned on.
        Machine forced = Machine.fromLanServer(
            new LanServer("p", "192.168.1.9", false, null, null, DeviceCategory.PRINTER, true), null);
        assertThat(forced.effectiveSshAccess()).isTrue();
    }

    @Test
    void effectiveSshAccess_noOverride_usesDefault() {
        PeerConfiguration peer = new PeerConfiguration("srv", "srv", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null, null);
        assertThat(Machine.fromPeer(peer, null).effectiveSshAccess()).isTrue();
    }

    // --- Vaier-server singleton (#311) ---

    @Test
    void vaierServer_hasCanonicalNameServerCategory_andDefaultsSshOn() {
        Machine m = Machine.vaierServer(null);

        assertThat(m.name()).isEqualTo(LanAnchor.VAIER_SERVER_NAME);
        assertThat(m.type()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(m.deviceCategory()).isEqualTo(DeviceCategory.SERVER);
        assertThat(m.effectiveSshAccess()).isTrue();
    }

    @Test
    void vaierServer_honoursExplicitOverride() {
        assertThat(Machine.vaierServer(false).effectiveSshAccess()).isFalse();
        assertThat(Machine.vaierServer(true).effectiveSshAccess()).isTrue();
    }
}
