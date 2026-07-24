package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void composesAllThreeWhenEveryConditionHolds() {
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.SERVER), 2, true, true, false, new BackupFleet(List.of()));

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.PUBLISH, MachineNudge.Kind.BACK_UP, MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
        assertThat(nudges).allSatisfy(n -> assertThat(n.machineName()).isEqualTo("nas"));
    }

    @Test
    void composesNothingWhenNoConditionHolds() {
        BackupServer existing = new BackupServer("nas-borg", "nas", "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.PRINTER), 0, false, false, true, new BackupFleet(List.of(existing)));

        assertThat(nudges).isEmpty();
    }

    @Test
    void composesOnlyTheNudgesThatApply() {
        // storage-class + no backup server ⇒ DESIGNATE; but unreachable/no-cred ⇒ no BACK_UP;
        // and nothing publishable ⇒ no PUBLISH.
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.NAS), 0, false, false, false, new BackupFleet(List.of()));

        assertThat(nudges).extracting(MachineNudge::kind)
            .containsExactly(MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
    }
}
