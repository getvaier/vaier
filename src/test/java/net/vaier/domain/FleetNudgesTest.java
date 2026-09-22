package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FleetNudgesTest {

    private static Machine machine(String name) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, DeviceCategory.SERVER, null);
    }

    @Test
    void forFleet_leadsWithPeopleWaiting_thenClimbsTheLadder_capsAtThree_andIsEmptyForAFinishedFleet() {
        Machine m = machine("Apalveien 5");
        PublishableService exposed = new PublishableService(PublishableService.PublishableSource.PEER, m.id().value(), m.name(),
            "10.13.13.9", "grafana", 3000, null, false);
        // Everything undone at once: people waiting, nothing published, no backup server, no kit, no mail.
        List<AccessEntry> oneWaiting = List.of(
            AccessEntry.builder().email("new@example.com").role(Role.PENDING).groups(List.of()).build());
        FleetSignals allUndone = new FleetSignals(List.of(m), List.of(exposed), 0, new BackupFleet(List.of()),
            2, false, oneWaiting, false);

        assertThat(FleetNudges.forFleet(allUndone)).extracting(FleetNudge::kind).containsExactly(
            FleetNudge.Kind.LET_PEOPLE_IN, FleetNudge.Kind.PUBLISH, FleetNudge.Kind.DESIGNATE_BACKUP_SERVER);

        // A fresh install: the first rung and the one thing that is silent regardless.
        FleetSignals fresh = new FleetSignals(List.of(), List.of(), 0, new BackupFleet(List.of()), 0, false, List.of(), false);
        assertThat(FleetNudges.forFleet(fresh)).extracting(FleetNudge::kind)
            .containsExactly(FleetNudge.Kind.ADD_MACHINE, FleetNudge.Kind.CONFIGURE_SMTP);

        // Done means nothing to say — an empty state, not a checklist of ticks.
        BackupFleet served = new BackupFleet(List.of(
            new BackupServer("nas", m.id(), "192.168.3.3", 8022, "borg", "/backups", null, false)));
        FleetSignals finished = new FleetSignals(List.of(m), List.of(), 3, served, 2, true, List.of(), true);
        assertThat(FleetNudges.forFleet(finished)).isEmpty();
    }
}
