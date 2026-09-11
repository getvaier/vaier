package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure-domain assembler that composes a machine's applicable nudges from the individual
 * {@link MachineNudge} factories. The "should X fire?" decisions live in those factories; this only
 * collects the ones that apply — so these tests pin the composition (which fire, in what order),
 * not the per-nudge predicates (those are {@link MachineNudgeTest}).
 */
class MachineNudgesTest {

    private static Machine machine(DeviceCategory category) {
        return new Machine(MachineId.generate(), "nas", MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, category, null);
    }

    private static BackupJob job(boolean backupAsRoot) {
        return new BackupJob("nas", TestMachineIds.of("nas"), "nas-repo", List.of("/home"), List.of(),
            7, 4, 6, "zstd,6", true, backupAsRoot);
    }

    private static BackupRun incompleteRun(BackupJob theJob) {
        return BackupRun.fromExitCode(theJob, "run-1", Instant.EPOCH, Instant.EPOCH, 1,
            "/home/mqtt/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'\n");
    }

    /**
     * The signals every test here starts from, named rather than positional. The assembler used to take
     * eight of these in a row — several adjacent {@code Optional}s and booleans — which is a swap-two-
     * arguments bug waiting for a hurried edit.
     */
    private static MachineSignals.MachineSignalsBuilder signals() {
        return MachineSignals.builder()
            .publishableCount(0)
            .reachable(false)
            .hasCredential(false)
            .job(Optional.empty())
            .latestRun(Optional.empty())
            .fleet(new BackupFleet(List.of()))
            .networks(MachineNetworks.unknown())
            .routingHostNetworks(MachineNetworks.unknown())
            .containerStandings(List.of())
            .zone(ZoneId.of("Europe/Oslo"));
    }

    @Test
    void composesAllThreeWhenEveryConditionHolds() {
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().publishableCount(2).reachable(true).hasCredential(true).build());

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.PUBLISH, MachineNudge.Kind.BACK_UP, MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
        assertThat(nudges).allSatisfy(n -> assertThat(n.machineName()).isEqualTo("nas"));
    }

    @Test
    void composesNothingWhenNoConditionHolds() {
        BackupServer existing = new BackupServer("nas-borg", TestMachineIds.of("nas"), "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.PRINTER),
            signals().job(Optional.of(job(true))).fleet(new BackupFleet(List.of(existing))).build());

        assertThat(nudges).isEmpty();
    }

    @Test
    void composesOnlyTheNudgesThatApply() {
        // storage-class + no backup server ⇒ DESIGNATE; but unreachable/no-cred ⇒ no BACK_UP;
        // and nothing publishable ⇒ no PUBLISH.
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.NAS), signals().build());

        assertThat(nudges).extracting(MachineNudge::kind)
            .containsExactly(MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
    }

    @Test
    void aMachineWithAJobIsAlreadyProtected_andAnIncompleteRunAddsTheRootNudge() {
        // "Already protected" is not a boolean the caller works out and passes in — it is "this machine has
        // a job", which the assembler reads off the job it needs anyway for the back-up-as-root decision.
        BackupJob theJob = job(false);
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().reachable(true).hasCredential(true).job(Optional.of(theJob))
                .latestRun(Optional.of(incompleteRun(theJob))).build());

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.DESIGNATE_BACKUP_SERVER, MachineNudge.Kind.BACK_UP_AS_ROOT);
    }

    @Test
    void aDetectedNetworkNothingRoutesYetIsComposedLast() {
        // #333: the detected LAN rides in as one more already-cached signal, exactly like the others — the
        // assembler still decides nothing, it only gathers.
        MachineNetworks detected = MachineNetworks.parse("""
            2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0
            default via 192.168.1.1 dev eth0 proto dhcp metric 100
            """);

        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().networks(detected).build());

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.DESIGNATE_BACKUP_SERVER, MachineNudge.Kind.ROUTE_LAN);
        assertThat(nudges.get(1).value()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void withNothingReadOffTheMachine_thereIsNoRouteNudge() {
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().build());

        assertThat(nudges).extracting(MachineNudge::kind).doesNotContain(MachineNudge.Kind.ROUTE_LAN);
    }

    @Test
    void aMachineWithNoWayOutIsSaidFirst(){
        // #357: the only card here that is trouble rather than an invitation, so it leads. It rides in on
        // the same already-read networks the route-LAN nudge uses — nothing new is asked of the machine.
        MachineNetworks noWayOut = MachineNetworks.parse(
            "2: eno1    inet 192.168.3.20/24 brd 192.168.3.255 scope global eno1");

        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().publishableCount(1).networks(noWayOut).build());

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.NO_DEFAULT_ROUTE, MachineNudge.Kind.PUBLISH,
            MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
    }

    @Test
    void aMachineVaierHasNotReadWearsNoMissingRoute() {
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().build());

        assertThat(nudges).extracting(MachineNudge::kind)
            .doesNotContain(MachineNudge.Kind.NO_DEFAULT_ROUTE);
    }

    @Test
    void aContainerThatWasRunningAndIsNotIsSaidAmongTheTrouble() {
        // #356: trouble, not an invitation, so it sits with the missing route above every "you could
        // also…" card. One card per container, from what the 30-second scrape already saw.
        MachineContainerStanding gone = MachineContainerStanding.builder()
            .machineId(TestMachineIds.of("nas")).containerName("webtrees")
            .standing(ContainerStanding.GONE)
            .lastSeenRunning(Instant.parse("2026-09-11T09:15:00Z"))
            .troubledSince(Instant.parse("2026-09-11T09:47:00Z"))
            .build();

        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().publishableCount(1).containerStandings(List.of(gone)).build());

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.CONTAINER_TROUBLE, MachineNudge.Kind.PUBLISH,
            MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
    }

    @Test
    void aMachineWhoseContainersAreAllUpWearsNoContainerCard() {
        List<MachineNudge> nudges = MachineNudges.forMachine(machine(DeviceCategory.SERVER),
            signals().build());

        assertThat(nudges).extracting(MachineNudge::kind)
            .doesNotContain(MachineNudge.Kind.CONTAINER_TROUBLE);
    }
}
