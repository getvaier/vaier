package net.vaier.domain;

import java.time.Instant;
import java.util.Optional;
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

    @Test
    void aMachineSwitchedOffOnPurpose_carriesWhenItWasMarked_fromWhereverItIsStored() {
        Instant since = Instant.parse("2026-09-14T08:00:00Z");
        PeerConfiguration peer = new PeerConfiguration("relay", "Relay", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null, null, MachineId.generate(), null, since);
        LanServer server = new LanServer("roon", "192.168.3.118", false, null).withSwitchedOffSince(since);

        assertThat(Machine.fromPeer(peer, null).switchedOffSince()).isEqualTo(since);
        assertThat(Machine.fromLanServer(server, null).switchedOffSince()).isEqualTo(since);
        assertThat(Machine.fromLanServer(server.withSwitchedOffSince(null), null).isSwitchedOffOnPurpose()).isFalse();
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

    // --- names are labels: two machines may wear the same one (§6.22) ---

    @Test
    void twoMachinesMayShareAName_andAreStillDifferentMachines() {
        // What the whole identity refactor was for. Machine.nameIsTaken and Machine.hasSameName are gone:
        // they existed because records were keyed by name, so a duplicate silently overwrote or mis-routed.
        // Everything is keyed by MachineId now, so a name is free to be whatever an operator finds useful —
        // and two houses may each have a "NAS" without Vaier ever confusing one for the other.
        Machine here = new Machine(MachineId.generate(), "NAS", MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            "192.168.3.0/24", "192.168.3.50", true, 2375, DeviceCategory.NAS, null);
        Machine there = new Machine(MachineId.generate(), "NAS", MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            "192.168.1.0/24", "192.168.1.50", true, 2375, DeviceCategory.NAS, null);

        assertThat(here.name()).isEqualTo(there.name());
        assertThat(here.id()).isNotEqualTo(there.id());
        assertThat(here).isNotEqualTo(there);
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
    void acceptsSetupScript_lanServerAppliance_false() {
        LanServer pool = new LanServer("Pool controller", "192.168.1.113", false, null, "Opensprinkler",
            DeviceCategory.IOT);

        assertThat(Machine.fromLanServer(pool, "192.168.1.0/24").acceptsSetupScript()).isFalse();
    }

    @Test
    void acceptsSetupScript_lanServerNas_true() {
        LanServer nas = new LanServer("NAS", "192.168.3.3", true, 2375, null, DeviceCategory.NAS);

        assertThat(Machine.fromLanServer(nas, "192.168.3.0/24").acceptsSetupScript()).isTrue();
    }

    @Test
    void acceptsSetupScript_applianceCategoryThatRunsDocker_true_itIsAComputerWhateverItIsCalled() {
        // A Roon server is an Ubuntu NUC filed under MEDIA. Docker on it is the whole reason for the script.
        LanServer roon = new LanServer("Roon server", "192.168.3.118", true, 2375, "Ubuntu on Intel NUC",
            DeviceCategory.MEDIA);

        assertThat(Machine.fromLanServer(roon, "192.168.3.0/24").acceptsSetupScript()).isTrue();
    }

    @Test
    void acceptsSetupScript_applianceCategoryVaierMaySshInto_true() {
        LanServer box = new LanServer("Roon server", "192.168.3.118", false, null, null,
            DeviceCategory.MEDIA, true, MachineId.generate());

        assertThat(Machine.fromLanServer(box, "192.168.3.0/24").acceptsSetupScript()).isTrue();
    }

    @Test
    void acceptsSetupScript_vpnPeer_false_itsScriptComesWithItsConfig() {
        assertThat(Machine.fromPeer(new PeerConfiguration("nuc", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, "192.168.3.0/24", "192.168.3.50"), null).acceptsSetupScript()).isFalse();
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
            MachineType.UBUNTU_SERVER, null, null, null, null, false, MachineId.generate(), null);
        Machine machine = Machine.fromPeer(peer, null);
        assertThat(machine.effectiveSshAccess()).isFalse();

        // A printer that would default to false, pinned on.
        Machine forced = Machine.fromLanServer(
            new LanServer("p", "192.168.1.9", false, null, null, DeviceCategory.PRINTER, true,
                MachineId.generate()), null);
        assertThat(forced.effectiveSshAccess()).isTrue();
    }

    @Test
    void effectiveSshAccess_noOverride_usesDefault() {
        PeerConfiguration peer = new PeerConfiguration("srv", "srv", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null, null, MachineId.generate(), null);
        assertThat(Machine.fromPeer(peer, null).effectiveSshAccess()).isTrue();
    }

    // --- Vaier-server singleton (#311) ---

    @Test
    void vaierServer_hasCanonicalNameServerCategory_andDefaultsSshOn() {
        Machine m = Machine.vaierServer(MachineId.generate(), null);

        assertThat(m.name()).isEqualTo(LanAnchor.VAIER_SERVER_NAME);
        assertThat(m.type()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(m.deviceCategory()).isEqualTo(DeviceCategory.SERVER);
        assertThat(m.effectiveSshAccess()).isTrue();
    }

    @Test
    void vaierServer_runsDocker() {
        // The Vaier server host is itself a Docker engine — it runs the whole compose stack (WireGuard,
        // Traefik, oauth2-proxy, Vaier). So its Machine projection must say it runs Docker, or the Explorer
        // tree never grows a `containers` entry for it and its own containers stay invisible.
        assertThat(Machine.vaierServer(MachineId.generate(), null).runsDocker()).isTrue();
    }

    @Test
    void vaierServer_honoursExplicitOverride() {
        assertThat(Machine.vaierServer(MachineId.generate(), false).effectiveSshAccess()).isFalse();
        assertThat(Machine.vaierServer(MachineId.generate(), true).effectiveSshAccess()).isTrue();
    }

    // --- which machines could ever relay a LAN (#333) -------------------------------------------------

    @Test
    void canRelayALan_aServerPeerCan() {
        PeerConfiguration peer = new PeerConfiguration("srv", "srv", "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, null, null, null, null, null, MachineId.generate(), null);
        assertThat(Machine.fromPeer(peer, null).canRelayALan()).isTrue();
    }

    @Test
    void canRelayALan_aPersonalDeviceCannot() {
        PeerConfiguration phone = new PeerConfiguration("phone", "phone", "10.13.13.8", "",
            MachineType.MOBILE_CLIENT, null, null, null, null, null, MachineId.generate(), null);
        assertThat(Machine.fromPeer(phone, null).canRelayALan()).isFalse();
    }

    @Test
    void canRelayALan_aLanServerCannot() {
        // It has no tunnel of its own — it is reached through a relay, it is not one.
        Machine printer = Machine.fromLanServer(
            new LanServer("p", "192.168.1.9", false, null, null, DeviceCategory.PRINTER, null,
                MachineId.generate()), "192.168.1.0/24");
        assertThat(printer.canRelayALan()).isFalse();
    }

    // --- what to call a machine in something a person reads -------------------------------------------

    /**
     * A machine's name is a label, and a record keyed by identity outlives it. What to call the machine
     * when the fleet still has it is easy; what to call it when the fleet does not is a decision, and one
     * that was being made four different ways — in the recovery sheet, in two admin emails and in a DTO.
     * It lives here so those four say the same thing.
     */
    @Test
    void labelFor_isTheMachinesNameWhenTheFleetStillHasIt() {
        MachineId id = MachineId.generate();

        assertThat(Machine.labelFor(id, Optional.of("Colina 27"))).isEqualTo("Colina 27");
    }

    @Test
    void labelFor_saysTheMachineIsGone_andStillNamesIt_whenTheFleetDoesNot() {
        // Not blank, and not a bare UUID. A person reading an alert or a recovery sheet has to be able to
        // tell "this record points at a machine you no longer have" from "this record is fine" — and then
        // has to be able to act on it, which needs the id.
        MachineId id = MachineId.of("636a121f-1da5-4b02-9b3a-1e80256e0a07");

        String label = Machine.labelFor(id, Optional.empty());

        assertThat(label).contains("no longer");
        assertThat(label).contains("636a121f-1da5-4b02-9b3a-1e80256e0a07");
    }

    /** A blank stored name is no name at all — it must not render as an empty label. */
    @Test
    void labelFor_treatsABlankNameAsNoName() {
        MachineId id = MachineId.generate();

        assertThat(Machine.labelFor(id, Optional.of("  "))).contains("no longer");
    }

    /**
     * "Does this machine run a shell Vaier can reach?" — the eligibility every fleet-wide operation that
     * needs a shell asks: distributing a fleet credential, signing Claude in. It lives here because it is
     * a fact about a machine, not about either feature, and because the alternative is each feature
     * keeping its own copy of a two-clause rule to get subtly wrong.
     */
    @Test
    void knowsWhetherItRunsAShellVaierCanReach() {
        Machine server = machine(MachineType.LAN_SERVER, DeviceCategory.SERVER, null);
        Machine phone = machine(MachineType.MOBILE_CLIENT, DeviceCategory.PHONE, null);

        assertThat(server.runsAShellVaierCanReach(true)).isTrue();
        // No login stored — Vaier is not allowed to open a session there at all.
        assertThat(server.runsAShellVaierCanReach(false)).isFalse();
        // Nowhere to run a shell, whatever credential Vaier might hold.
        assertThat(phone.runsAShellVaierCanReach(true)).isFalse();
    }

    /** The operator's own SSH override wins, in both directions. */
    @Test
    void respectsTheOperatorsSshOverrideWhenJudgingShellReach() {
        assertThat(machine(MachineType.LAN_SERVER, DeviceCategory.SERVER, false)
            .runsAShellVaierCanReach(true)).isFalse();
        assertThat(machine(MachineType.MOBILE_CLIENT, DeviceCategory.PHONE, true)
            .runsAShellVaierCanReach(true)).isTrue();
    }

    private static Machine machine(MachineType type, DeviceCategory category, Boolean sshOverride) {
        return new Machine(TestMachineIds.of("m"), "m", type, null, null, null, null, null, null,
            null, null, null, false, null, category, sshOverride);
    }
}
