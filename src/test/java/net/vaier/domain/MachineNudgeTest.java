package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineNudgeTest {

    private static Machine storageMachine(String name, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, category, null);
    }

    // --- shape / invariants ---

    @Test
    void rejectsBlankFields() {
        assertThatThrownBy(() -> new MachineNudge("", MachineNudge.Kind.PUBLISH, "t", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", null, "t", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, " ", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, "t", "", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, "t", "e", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesMachineKindTitleEvidenceAction() {
        MachineNudge nudge = new MachineNudge("nas", MachineNudge.Kind.BACK_UP, "Back up nas", "why", "do it");
        assertThat(nudge.machineName()).isEqualTo("nas");
        assertThat(nudge.kind()).isEqualTo(MachineNudge.Kind.BACK_UP);
        assertThat(nudge.title()).isEqualTo("Back up nas");
        assertThat(nudge.evidence()).isEqualTo("why");
        assertThat(nudge.action()).isEqualTo("do it");
    }

    // --- PUBLISH predicate ---

    @Test
    void publish_firesWhenServicesExposed() {
        Optional<MachineNudge> nudge = MachineNudge.publish("alice", 3);
        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.PUBLISH);
        assertThat(nudge.get().evidence()).contains("3 services");
    }

    @Test
    void publish_emptyWhenNothingExposed() {
        assertThat(MachineNudge.publish("alice", 0)).isEmpty();
        assertThat(MachineNudge.publish("alice", -1)).isEmpty();
    }

    // --- BACK_UP predicate ---

    @Test
    void backUp_firesWhenReachableCredentialedAndUnprotected() {
        assertThat(MachineNudge.backUp("nas", true, true, false)).isPresent();
    }

    @Test
    void backUp_emptyWhenMissingAnySignal() {
        assertThat(MachineNudge.backUp("nas", false, true, false)).isEmpty();   // unreachable
        assertThat(MachineNudge.backUp("nas", true, false, false)).isEmpty();   // no credential
        assertThat(MachineNudge.backUp("nas", true, true, true)).isEmpty();     // already protected
    }

    // --- DESIGNATE_BACKUP_SERVER predicate (both directions) ---

    @Test
    void designate_firesForStorageClassMachineWhenNoServerExists() {
        Optional<MachineNudge> nudge = MachineNudge.designateBackupServer(
            storageMachine("nas", DeviceCategory.NAS), new BackupFleet(List.of()));
        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);

        assertThat(MachineNudge.designateBackupServer(
            storageMachine("box", DeviceCategory.SERVER), new BackupFleet(List.of()))).isPresent();
    }

    @Test
    void designate_emptyWhenABackupServerAlreadyExists() {
        BackupServer existing = new BackupServer("nas-borg", TestMachineIds.of("nas"), "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("nas", DeviceCategory.NAS), new BackupFleet(List.of(existing)))).isEmpty();
    }

    @Test
    void designate_emptyForNonStorageClassMachine() {
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("printer", DeviceCategory.PRINTER), new BackupFleet(List.of()))).isEmpty();
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("phone", DeviceCategory.PHONE), new BackupFleet(List.of()))).isEmpty();
    }

    // --- BACK_UP_AS_ROOT predicate (#334) ---
    //
    // The checkbox used to ask this in advance, of an operator with no evidence. The nudge asks it once
    // there IS evidence: a run that settled incomplete because borg was denied on files the job protects.

    private static BackupJob job(boolean backupAsRoot) {
        return new BackupJob("colina", TestMachineIds.of("colina"), "colina-repo", List.of("/home"),
            List.of(), 7, 4, 6, "zstd,6", true, backupAsRoot);
    }

    /** A settled run of {@code job} carrying {@code output} as borg's captured tail. */
    private static BackupRun runWith(BackupJob theJob, int exitCode, String output) {
        return BackupRun.fromExitCode(theJob, "run-1", Instant.EPOCH, Instant.EPOCH, exitCode, output);
    }

    private static final String DENIALS = """
        /home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
        /home/pihole/gravity.db: open: [Errno 13] Permission denied: 'gravity.db'
        """;

    @Test
    void backUpAsRoot_firesOnAnIncompleteRunThatLostFilesToPermissions() {
        BackupJob theJob = job(false);
        Optional<MachineNudge> nudge = MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, DENIALS)), Optional.of(theJob));

        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.BACK_UP_AS_ROOT);
        assertThat(nudge.get().machineName()).isEqualTo("colina");
        // The count is the fact that decides whether the operator acts, and the files are the evidence.
        assertThat(nudge.get().title()).contains("2 files");
        assertThat(nudge.get().evidence()).contains("/home/mqtt/data/mosquitto.db", "/home/pihole/gravity.db");
        assertThat(nudge.get().action()).isNotBlank();
    }

    @Test
    void backUpAsRoot_saysItInTheSingularForOneLostFile() {
        BackupJob theJob = job(false);
        Optional<MachineNudge> nudge = MachineNudge.backUpAsRoot("colina", Optional.of(runWith(theJob, 1,
            "/home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'x'\n")), Optional.of(theJob));

        assertThat(nudge).isPresent();
        assertThat(nudge.get().title()).contains("1 file").doesNotContain("1 files");
    }

    @Test
    void backUpAsRoot_emptyWhenTheJobAlreadyBacksUpAsRoot() {
        // Root already reads everything; whatever it could not read, this nudge cannot fix.
        BackupJob asRoot = job(true);
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(asRoot, 1, DENIALS)), Optional.of(asRoot))).isEmpty();
    }

    @Test
    void backUpAsRoot_emptyWhenTheRunFailedForAnyOtherReason() {
        BackupJob theJob = job(false);
        // A plain warning (a file changed mid-read) — nothing was denied, nothing is missing.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, "file changed while we backed it up")), Optional.of(theJob)))
            .isEmpty();
        // borg gave up entirely: there is no archive at all, which is a different problem.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 2, "Connection refused")), Optional.of(theJob))).isEmpty();
        // A clean run says nothing, even if its tail happens to mention permissions.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 0, "2 files skipped (permission denied)")), Optional.of(theJob)))
            .isEmpty();
    }

    @Test
    void backUpAsRoot_emptyWithoutAJobOrARun() {
        BackupJob theJob = job(false);
        assertThat(MachineNudge.backUpAsRoot("colina", Optional.empty(), Optional.of(theJob))).isEmpty();
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, DENIALS)), Optional.empty())).isEmpty();
    }

    // --- ROUTE_LAN predicate (#333) ---

    private static Machine relay(String lanCidr) {
        return new Machine(MachineId.generate(), "Colina 27", MachineType.UBUNTU_SERVER, "pk",
            "10.13.13.3/32", null, null, null, null, null, lanCidr, null, true, null,
            DeviceCategory.SERVER, null);
    }

    private static MachineNetworks readingOf(String output) {
        return MachineNetworks.parse(output);
    }

    private static final String COLINA = """
        2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0
        default via 192.168.1.1 dev eth0 proto dhcp metric 100
        """;

    /** The EC2 Vaier server, on a VPC subnet nothing in the fleet shares. */
    private static final MachineNetworks EC2 = MachineNetworks.parse("""
        2: ens5    inet 172.31.37.204/20 brd 172.31.47.255 scope global ens5
        default via 172.31.32.1 dev ens5 proto dhcp metric 100
        """);

    @Test
    void routeLan_offersWhatVaierReadOffTheMachine_namingWhereItCameFrom() {
        Optional<MachineNudge> nudge = MachineNudge.routeLan(relay(null), readingOf(COLINA), EC2);

        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.ROUTE_LAN);
        assertThat(nudge.get().title()).contains("Colina 27").contains("192.168.1.0/24");
        // The evidence must name where the value came from — the machine itself, and the interface.
        assertThat(nudge.get().evidence()).contains("eth0");
        // The operator clicks yes; the CIDR they are saying yes to travels with the nudge, so the browser
        // never has to re-derive it from the copy on screen.
        assertThat(nudge.get().value()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void routeLan_emptyWhenTheNetworkIsAlreadyRouted() {
        assertThat(MachineNudge.routeLan(relay("192.168.1.0/24"), readingOf(COLINA), EC2)).isEmpty();
        // Even a different CIDR: the operator has already answered this question for this machine.
        assertThat(MachineNudge.routeLan(relay("10.9.0.0/16"), readingOf(COLINA), EC2)).isEmpty();
    }

    @Test
    void routeLan_emptyWhenVaierCouldNotReadTheMachine() {
        // No guess, no empty form — a machine Vaier cannot read is simply not nudged.
        assertThat(MachineNudge.routeLan(relay(null), MachineNetworks.unknown(), EC2)).isEmpty();
        assertThat(MachineNudge.routeLan(relay(null), readingOf("sh: ip: command not found"), EC2)).isEmpty();
    }

    @Test
    void routeLan_emptyWhenTheDetectedNetworkWouldBlackholeTheHostThatRoutesIt() {
        // The Vaier server answers on 172.31.37.204. Routing 172.31.32.0/20 into wg0 there — which is
        // exactly what accepting would do — severs Vaier from its own network. Never offered.
        MachineNetworks sameSubnetAsVaier = readingOf("""
            2: eth0    inet 172.31.40.9/20 brd 172.31.47.255 scope global eth0
            default via 172.31.32.1 dev eth0 proto dhcp metric 100
            """);

        assertThat(MachineNudge.routeLan(relay(null), sameSubnetAsVaier, EC2)).isEmpty();
    }

    @Test
    void routeLan_theVaierServerIsNeverOfferedItsOwnNetwork() {
        // Falls out of the guard rather than needing a rule of its own: the machine's detected LAN is the
        // Vaier server's uplink network, so it contains the Vaier server's uplink address by construction.
        Machine vaierServer = new Machine(MachineId.generate(), "Vaier server", MachineType.UBUNTU_SERVER,
            null, null, null, null, null, null, null, null, null, true, null, DeviceCategory.SERVER, null);

        assertThat(MachineNudge.routeLan(vaierServer, EC2, EC2)).isEmpty();
    }

    @Test
    void routeLan_withNoReadingOfTheRoutingHost_stillOffers() {
        // Not knowing the Vaier server's own address is not evidence of danger, and refusing on "unknown"
        // is how this feature would silently never fire.
        assertThat(MachineNudge.routeLan(relay(null), readingOf(COLINA), MachineNetworks.unknown()))
            .isPresent();
    }

    @Test
    void routeLan_onlyAMachineThatCouldRelayAtAll() {
        // A LAN server has no tunnel to route into, and a personal device is not a relay.
        Machine lanServer = new Machine(MachineId.generate(), "printer", MachineType.LAN_SERVER, null, null,
            null, null, null, null, null, null, "192.168.1.11", false, null, DeviceCategory.PRINTER, null);
        Machine laptop = new Machine(MachineId.generate(), "laptop", MachineType.MOBILE_CLIENT, "pk",
            "10.13.13.8/32", null, null, null, null, null, null, null, false, null,
            DeviceCategory.LAPTOP, null);

        assertThat(MachineNudge.routeLan(lanServer, readingOf(COLINA), EC2)).isEmpty();
        assertThat(MachineNudge.routeLan(laptop, readingOf(COLINA), EC2)).isEmpty();
    }

    // --- NO_DEFAULT_ROUTE predicate (#357) ---

    /** What the observed fault looks like: an address, an on-link route, and no way out. */
    private static final String NO_WAY_OUT = """
        1: lo    inet 127.0.0.1/8 scope host lo
        2: eno1    inet 192.168.3.20/24 brd 192.168.3.255 scope global eno1
        """;

    @Test
    void noDefaultRoute_saysWhatItCostsAndWhatVaierCanDoAboutIt() {
        Optional<MachineNudge> nudge = MachineNudge.noDefaultRoute("Apalveien 5", readingOf(NO_WAY_OUT));

        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.NO_DEFAULT_ROUTE);
        assertThat(nudge.get().title()).contains("No default route");
        // The evidence is what Vaier actually read off the machine — the interface and the address it holds
        // — so the operator can see where the verdict came from without leaving the pane.
        assertThat(nudge.get().evidence()).contains("eno1").contains("192.168.3.20");
        // And the honest part: this one is not a capability to adopt. Vaier can see it and cannot fix it.
        assertThat(nudge.get().action()).contains("cannot reach the internet");
        assertThat(nudge.get().action()).contains("Vaier can see this");
        assertThat(nudge.get().value()).isNull();
    }

    @Test
    void noDefaultRoute_silentWhenTheMachineHasAWayOut() {
        assertThat(MachineNudge.noDefaultRoute("Colina 27", readingOf(COLINA))).isEmpty();
    }

    @Test
    void noDefaultRoute_silentWhenVaierHasNotReadTheMachine() {
        // Unknown is not "no". A machine nobody has asked must never wear a fault it was never observed to
        // have — it is the whole reason the standing is three-valued.
        assertThat(MachineNudge.noDefaultRoute("nas", MachineNetworks.unknown())).isEmpty();
        assertThat(MachineNudge.noDefaultRoute("nas", readingOf("sh: ip: command not found"))).isEmpty();
    }
}
