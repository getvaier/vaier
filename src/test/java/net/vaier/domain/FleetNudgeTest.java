package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet-level nudge ladder (#336): what to do next, said on the page everyone lands on, with the evidence
 * Vaier used. Each rung fires only on its own condition and vanishes the moment it clears.
 */
class FleetNudgeTest {

    private static Machine machine(String name, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, category, null);
    }

    private static PublishableService exposed(Machine on, String container) {
        return new PublishableService(PublishableService.PublishableSource.PEER, on.id().value(), on.name(), "10.13.13.9",
            container, 3000, null, false);
    }

    @Test
    void addMachine_letPeopleIn_andConfigureSmtp_fireOnTheirOwnFact_andSayWhy() {
        assertThat(FleetNudge.addMachine(0)).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.ADD_MACHINE);
            assertThat(n.title()).isEqualTo("Add the machine your services run on");
            assertThat(n.evidence()).isEqualTo("Nothing is connected yet");
        });
        assertThat(FleetNudge.addMachine(1)).isEmpty();

        AccessEntry pending1 = AccessEntry.builder().email("a@example.com").role(Role.PENDING).groups(List.of()).build();
        AccessEntry pending2 = AccessEntry.builder().email("b@example.com").role(Role.PENDING).groups(List.of()).build();
        AccessEntry admin = AccessEntry.builder().email("me@example.com").role(Role.ADMIN).groups(List.of()).build();
        assertThat(FleetNudge.letPeopleIn(List.of(pending1, pending2, admin))).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.LET_PEOPLE_IN);
            assertThat(n.title()).isEqualTo("2 people are waiting to be let in");
            assertThat(n.evidence()).isEqualTo("Signed in, blocked, awaiting approval");
        });
        assertThat(FleetNudge.letPeopleIn(List.of(pending1, admin)).map(FleetNudge::title))
            .hasValue("1 person is waiting to be let in");
        assertThat(FleetNudge.letPeopleIn(List.of(admin))).isEmpty();

        assertThat(FleetNudge.configureSmtp(false)).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.CONFIGURE_SMTP);
            assertThat(n.title()).isEqualTo("Vaier cannot tell you when something breaks");
            assertThat(n.evidence()).isEqualTo("Disk, backup and machine alerts are all silent");
        });
        assertThat(FleetNudge.configureSmtp(true)).isEmpty();
    }

    @Test
    void publish_namesTheMachineWithTheMostUnroutedServices_andOnlyWhileNothingIsPublishedYet() {
        Machine apalveien = machine("Apalveien 5", DeviceCategory.SERVER);
        Machine colina = machine("Colina 27", DeviceCategory.SERVER);
        List<PublishableService> exposed = List.of(exposed(apalveien, "grafana"), exposed(apalveien, "pihole"),
            exposed(apalveien, "openhab"), exposed(colina, "netdata"));

        Optional<FleetNudge> nudge = FleetNudge.publish(List.of(apalveien, colina), exposed, 0);

        assertThat(nudge).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.PUBLISH);
            assertThat(n.title()).isEqualTo("Publish 3 services on Apalveien 5");
            assertThat(n.evidence()).isEqualTo("3 exposed there, none routed through Vaier");
            assertThat(n.value()).isEqualTo(apalveien.id().value());
        });
        // Once anything is published the per-machine nudges carry on; the fleet rung has done its job.
        assertThat(FleetNudge.publish(List.of(apalveien, colina), exposed, 1)).isEmpty();
        assertThat(FleetNudge.publish(List.of(apalveien), List.of(), 0)).isEmpty();
    }

    @Test
    void designateBackupServer_firesWhileTheFleetHasNone_andOffersANasWhenThereIsOne() {
        Machine server = machine("Apalveien 5", DeviceCategory.SERVER);
        Machine nas = machine("NAS", DeviceCategory.NAS);
        BackupFleet none = new BackupFleet(List.of());

        assertThat(FleetNudge.designateBackupServer(List.of(server, nas), none)).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.DESIGNATE_BACKUP_SERVER);
            assertThat(n.title()).isEqualTo("Nothing in your fleet is backed up");
            assertThat(n.evidence()).isEqualTo("2 machines, no backup server designated — NAS could hold it");
            assertThat(n.value()).isEqualTo(nas.id().value());
        });
        assertThat(FleetNudge.designateBackupServer(List.of(server), none)).hasValueSatisfying(n -> {
            assertThat(n.evidence()).isEqualTo("1 machine, no backup server designated");
            assertThat(n.value()).isNull();   // nothing to offer: the operator chooses
        });
        assertThat(FleetNudge.designateBackupServer(List.of(), none)).isEmpty();
        assertThat(FleetNudge.designateBackupServer(List.of(server, nas),
            new BackupFleet(List.of(new BackupServer("nas", nas.id(), "192.168.3.3", 8022, "borg", "/backups", null, false)))))
            .isEmpty();
    }

    @Test
    void writeSurvivalKit_firesOnlyWhenRepositoriesExist_andNoKitWasEverWritten() {
        assertThat(FleetNudge.writeSurvivalKit(3, false)).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.WRITE_SURVIVAL_KIT);
            assertThat(n.title()).isEqualTo("If this server died, nothing could open your backups");
            assertThat(n.evidence()).isEqualTo("3 repositories, no survival kit written");
        });
        assertThat(FleetNudge.writeSurvivalKit(3, true)).isEmpty();
        assertThat(FleetNudge.writeSurvivalKit(0, false)).isEmpty();
    }
}
