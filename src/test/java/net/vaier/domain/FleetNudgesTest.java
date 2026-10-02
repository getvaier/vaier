package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FleetNudgesTest {

    /** A server peer that is answering — one that is not would be trouble of its own. */
    private static Machine machine(String name) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            String.valueOf(System.currentTimeMillis() / 1000), null, null, null, null, true, null,
            DeviceCategory.SERVER, null);
    }

    @Test
    void forFleet_leadsWithPeopleWaiting_thenClimbsTheLadder_capsAtThree_andIsEmptyForAFinishedFleet() {
        Machine m = machine("Apalveien 5");
        PublishableService exposed = new PublishableService(PublishableService.PublishableSource.PEER, m.id().value(), m.name(),
            "10.13.13.9", "grafana", 3000, null, false);
        // Everything undone at once: people waiting, nothing published, no backup server, no kit, no mail.
        List<AccessEntry> oneWaiting = List.of(
            AccessEntry.builder().email("new@example.com").role(Role.PENDING).groups(List.of()).build());
        FleetSignals allUndone = FleetSignals.builder().machines(List.of(m)).publishable(List.of(exposed))
            .repositoryCount(2).accessEntries(oneWaiting).build();

        assertThat(FleetNudges.forFleet(allUndone)).extracting(FleetNudge::kind).containsExactly(
            FleetNudge.Kind.LET_PEOPLE_IN, FleetNudge.Kind.PUBLISH, FleetNudge.Kind.DESIGNATE_BACKUP_SERVER);

        // A fresh install: the first rung and the one thing that is silent regardless.
        assertThat(FleetNudges.forFleet(FleetSignals.builder().build())).extracting(FleetNudge::kind)
            .containsExactly(FleetNudge.Kind.ADD_MACHINE, FleetNudge.Kind.CONFIGURE_SMTP);

        // Done means nothing to say — an empty state, not a checklist of ticks.
        BackupFleet served = new BackupFleet(List.of(
            new BackupServer("nas", m.id(), "192.168.3.3", 8022, "borg", "/backups", null, false)));
        FleetSignals finished = FleetSignals.builder().machines(List.of(m)).publishedCount(3).fleet(served)
            .repositoryCount(2).survivalKitWritten(true).smtpConfigured(true).build();
        assertThat(FleetNudges.forFleet(finished)).isEmpty();
    }

    @Test
    void forFleet_saysTroubleFirst_mostSevereFirst_neverCapsIt_andKeepsImageUpdatesForLast() {
        Machine printer = new Machine(MachineId.generate(), "Printer", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.3.20", false, null, DeviceCategory.PRINTER, null);
        Machine roon = new Machine(MachineId.generate(), "Roon", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.3.21", false, null, DeviceCategory.MEDIA, null);
        Machine nas = new Machine(MachineId.generate(), "NAS", MachineType.LAN_SERVER, null, null, null, null,
            null, null, null, null, "192.168.3.3", true, 2375, DeviceCategory.NAS, null);
        Machine newcomer = machine("Fresh");
        DockerService stale = new DockerService("1", "pihole", "pihole/pihole", "1", List.of(), List.of(), "running",
            "sha256:a", UpdateAvailability.UPDATE_AVAILABLE);
        PublishableService exposed = new PublishableService(PublishableService.PublishableSource.PEER,
            newcomer.id().value(), newcomer.name(), "10.13.13.9", "grafana", 3000, null, false);

        FleetSignals troubled = FleetSignals.builder()
            .machines(List.of(printer, roon, nas, newcomer))
            .publishable(List.of(exposed))
            .accessEntries(List.of(AccessEntry.builder().email("a@example.com").role(Role.PENDING)
                .groups(List.of()).build()))
            .lanReachability(Map.of("192.168.3.20", Reachability.DOWN, "192.168.3.21", Reachability.DOWN,
                "192.168.3.3", Reachability.OK))
            .diskStandings(List.of(new MachineDiskStanding(nas.id(), "/volume1", 92, 80, 1, 2)))
            .enrolmentRequests(List.of(EnrolmentRequest.builder().code("4821").name("Ruten")
                .machineType(MachineType.MOBILE_CLIENT).build()))
            .preFlight(new PreFlight(List.of(new PreFlightFinding(PreFlightFinding.Check.WIREGUARD,
                "WireGuard is not answering.", "Check it."))))
            .containers(Map.of(nas.id().value(), List.of(stale)))
            .build();

        // Someone waiting on a code is time-bound, so it leads; then Vaier's own basics, then each machine's
        // trouble; then the ladder, still capped; and last the images, which are worth knowing and never urgent.
        assertThat(FleetNudges.forFleet(troubled)).extracting(FleetNudge::kind).containsExactly(
            FleetNudge.Kind.DEVICE_WAITING, FleetNudge.Kind.VAIER_BASICS,
            FleetNudge.Kind.MACHINE_DOWN, FleetNudge.Kind.MACHINE_DOWN, FleetNudge.Kind.DISK_FULL,
            FleetNudge.Kind.LET_PEOPLE_IN, FleetNudge.Kind.PUBLISH, FleetNudge.Kind.DESIGNATE_BACKUP_SERVER,
            FleetNudge.Kind.IMAGE_UPDATES);
        // How loud each row is said here, never re-decided by whatever draws it.
        assertThat(FleetNudges.forFleet(troubled)).extracting(n -> n.kind().tone()).containsExactly(
            FleetNudge.Tone.INVITE, FleetNudge.Tone.VERDICT, FleetNudge.Tone.VERDICT, FleetNudge.Tone.VERDICT,
            FleetNudge.Tone.VERDICT, FleetNudge.Tone.INVITE, FleetNudge.Tone.INVITE, FleetNudge.Tone.INVITE,
            FleetNudge.Tone.WATCH);
    }
}
