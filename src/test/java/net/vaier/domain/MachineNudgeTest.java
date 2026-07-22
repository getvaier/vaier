package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineNudgeTest {

    private static Machine storageMachine(String name, DeviceCategory category) {
        return new Machine(name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
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
        BackupServer existing = new BackupServer("nas-borg", "nas", "192.168.3.50",
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
}
