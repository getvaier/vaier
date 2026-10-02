package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet-level nudge ladder (#336): what to do next, said on the page everyone lands on, with the evidence
 * Vaier used. Each rung fires only on its own condition and vanishes the moment it clears. The trouble rows
 * of "Needs you" are rungs of the same ladder, judged here from the same already-cached facts.
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

    // --- trouble: what "Needs you" says before any rung of the ladder ---

    private static Machine lanServer(String name, String address, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.LAN_SERVER, null, null, null, null, null, null,
            null, null, address, false, null, category, null);
    }

    private static Machine peer(String name, MachineType type, String handshake) {
        return new Machine(MachineId.generate(), name, type, "pk-" + name, "10.13.13.9/32", "1.2.3.4", "51820",
            handshake, "1", "1", null, null, type.isServerType(), null, DeviceCategory.SERVER, null);
    }

    private static BackupJob job(Machine on, boolean enabled, boolean asRoot) {
        return new BackupJob(on.name(), on.id(), on.id().value(), List.of("/home"), List.of(), 7, 4, 6, "zstd,6",
            enabled, asRoot);
    }

    private static final String DENIALS = """
        /home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
        /home/pihole/gravity.db: open: [Errno 13] Permission denied: 'gravity.db'
        """;

    @Test
    void machinesDown_namesEachServerThatShouldAnswer_andDoesNot_withWhenItLastDid() {
        Machine printer = lanServer("Printer", "192.168.3.20", DeviceCategory.PRINTER);
        Machine roon = lanServer("Roon server", "192.168.3.118", DeviceCategory.MEDIA);
        Machine unprobed = lanServer("NAS", "192.168.3.3", DeviceCategory.NAS);
        Machine relay = peer("Colina 27", MachineType.UBUNTU_SERVER, "1790900000");
        Machine phone = peer("Ruten", MachineType.MOBILE_CLIENT, "1000");
        BackupJob roonJob = job(roon, true, false);

        List<FleetNudge> down = FleetNudge.machinesDown(FleetSignals.builder()
            .machines(List.of(printer, roon, unprobed, relay, phone))
            .lanReachability(Map.of("192.168.3.20", Reachability.DOWN, "192.168.3.118", Reachability.DOWN))
            .lanLastSeen(Map.of("192.168.3.20", 1790900000L))
            .backupJobs(List.of(roonJob))
            .latestRuns(List.of(BackupRun.failed(roonJob, "r1", Instant.EPOCH, "Could not reach it")))
            .zone(ZoneOffset.UTC)
            .build());

        // A phone that is not connected is away, not down; a LAN server nobody has probed is unknown.
        assertThat(down).extracting(FleetNudge::title).containsExactly(
            "Colina 27 is not answering", "Printer is not answering", "Roon server is not answering");
        assertThat(down).allSatisfy(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.MACHINE_DOWN);
            assertThat(n.kind().isTrouble()).isTrue();
        });
        assertThat(down).extracting(FleetNudge::evidence).containsExactly(
            "Last heard from 2 Oct at 00:13",
            "Last answered 2 Oct at 00:13",
            // A failed backup on a machine that is down is a consequence, so it is said here, not twice.
            "Has not answered since Vaier started · its last backup failed too");
        assertThat(down.get(1).value()).isEqualTo(printer.id().value());
    }

    @Test
    void backups_sayWhatWentWrongWithTheLastRun_andOfferTheFixVaierHas() {
        record Row(String why, boolean down, boolean enabled, boolean asRoot, Function<BackupJob, BackupRun> run,
                   FleetNudge.Kind expected) {}
        Machine up = peer("Colina 27", MachineType.UBUNTU_SERVER, String.valueOf(Instant.now().getEpochSecond()));
        Machine stale = peer("Colina 27", MachineType.UBUNTU_SERVER, "1000");
        for (Row row : List.of(
            new Row("a failure names what happened", false, true, false,
                j -> BackupRun.failed(j, "r", Instant.EPOCH, "Could not reach it"), FleetNudge.Kind.BACKUP_FAILED),
            new Row("no borg client is the one failure Vaier fixes", false, true, false,
                j -> BackupRun.borgMissing(j, "r", Instant.EPOCH, "Colina 27"), FleetNudge.Kind.BACKUP_NEEDS_READYING),
            new Row("files lost to permissions are read as root", false, true, false,
                j -> BackupRun.fromExitCode(j, "r", Instant.EPOCH, Instant.EPOCH, 1, DENIALS),
                FleetNudge.Kind.BACK_UP_AS_ROOT),
            new Row("root already reads everything, so only the verdict is left", false, true, true,
                j -> BackupRun.fromExitCode(j, "r", Instant.EPOCH, Instant.EPOCH, 1, DENIALS),
                FleetNudge.Kind.BACKUP_INCOMPLETE),
            new Row("a run that kept everything is not news", false, true, false,
                j -> BackupRun.fromExitCode(j, "r", Instant.EPOCH, Instant.EPOCH, 0, "ok"), null),
            new Row("a stopped job is not watched", false, false, false,
                j -> BackupRun.failed(j, "r", Instant.EPOCH, "x"), null),
            new Row("a machine that is down already says it", true, true, false,
                j -> BackupRun.failed(j, "r", Instant.EPOCH, "x"), null))) {
            Machine on = row.down() ? stale : up;
            BackupJob theJob = job(on, row.enabled(), row.asRoot());
            List<FleetNudge> said = FleetNudge.backups(FleetSignals.builder().machines(List.of(on))
                .backupJobs(List.of(theJob)).latestRuns(List.of(row.run().apply(theJob))).build());
            if (row.expected() == null) {
                assertThat(said).as(row.why()).isEmpty();
            } else {
                assertThat(said).as(row.why()).singleElement().satisfies(n -> {
                    assertThat(n.kind()).isEqualTo(row.expected());
                    assertThat(n.kind().isTrouble()).isTrue();
                    assertThat(n.value()).isEqualTo(on.id().value());
                });
            }
        }
        BackupJob plain = job(up, true, false);
        BackupRun lost = BackupRun.failed(plain, "r", Instant.EPOCH, "Could not reach 192.168.3.118:22 (No route to host)");
        assertThat(FleetNudge.backups(FleetSignals.builder().machines(List.of(up)).backupJobs(List.of(plain))
            .latestRuns(List.of(lost)).build())).singleElement().satisfies(n -> {
                assertThat(n.title()).isEqualTo("Colina 27's last backup failed");
                assertThat(n.evidence()).isEqualTo("Could not reach 192.168.3.118:22 (No route to host)");
            });
    }

    @Test
    void disks_overOrNearTheirThreshold_sayHowFull_andTheVaierServersOwnSaysWhatItRisks() {
        Machine nas = lanServer("NAS", "192.168.3.3", DeviceCategory.NAS);
        Machine server = Machine.vaierServer(MachineId.generate(), null);
        Machine roomy = lanServer("Rack server", "192.168.3.4", DeviceCategory.SERVER);

        List<FleetNudge> disks = FleetNudge.disks(FleetSignals.builder()
            .machines(List.of(nas, server, roomy))
            .vaierServer(server.id())
            .diskStandings(List.of(
                new MachineDiskStanding(nas.id(), "/volume1", 78, 80, 0, 3),
                new MachineDiskStanding(server.id(), "/", 91, 80, 1, 3),
                new MachineDiskStanding(roomy.id(), "/", 20, 80, 0, 1)))
            .build());

        assertThat(disks).extracting(FleetNudge::kind)
            .containsExactly(FleetNudge.Kind.DISK_FULL, FleetNudge.Kind.DISK_FILLING);
        assertThat(disks.get(0).title()).isEqualTo("Vaier server's disk is 91% full");
        assertThat(disks.get(0).evidence()).isEqualTo("/ is past its 80% threshold — Vaier itself may stop working");
        assertThat(disks.get(1).title()).isEqualTo("NAS's disk is 78% full");
        assertThat(disks.get(1).evidence()).isEqualTo("/volume1 is closing on its 80% threshold");
        assertThat(disks.get(1).value()).isEqualTo(nas.id().value());
    }

    @Test
    void vaierBasics_andTheRouteAudit_carryTheDomainsOwnSentences_andAHealthyServerSaysNothing() {
        PreFlight wrong = new PreFlight(List.of(
            new PreFlightFinding(PreFlightFinding.Check.WIREGUARD, "WireGuard is not answering.", "Check it."),
            new PreFlightFinding(PreFlightFinding.Check.DISK, "This server's own disk is full.", "Free space.")));
        // The disk finding is the Vaier server's disk row, which already says it on that machine's card.
        assertThat(FleetNudge.vaierBasics(wrong)).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.VAIER_BASICS);
            assertThat(n.title()).isEqualTo("WireGuard is not answering.");
            assertThat(n.evidence()).isEqualTo("Check it.");
        });
        assertThat(FleetNudge.vaierBasics(new PreFlight(List.of()))).isEmpty();

        ReverseProxyAudit audit = new ReverseProxyAudit(List.of(
            new ReverseProxyFinding(ReverseProxyFinding.Kind.UNREFERENCED_MIDDLEWARE, "old-redirect", "Left behind."),
            new ReverseProxyFinding(ReverseProxyFinding.Kind.UNROUTED_SERVICE, "old-svc", "Nothing sends to it.")));
        assertThat(FleetNudge.routeAudit(audit)).hasValueSatisfying(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.ROUTE_AUDIT);
            assertThat(n.title()).isEqualTo("2 entries in the reverse proxy config lead nowhere");
            assertThat(n.evidence()).isEqualTo("Vaier changed nothing — removing one is yours to decide");
            assertThat(n.detail()).containsExactly("Left behind.", "Nothing sends to it.");
        });
        assertThat(FleetNudge.routeAudit(new ReverseProxyAudit(List.of()))).isEmpty();
    }

    @Test
    void devicesWaiting_andImageUpdates_eachSayWhoOrWhere() {
        EnrolmentRequest ruten = EnrolmentRequest.builder().code("4821").name("Ruten")
            .machineType(MachineType.MOBILE_CLIENT).build();
        EnrolmentRequest pc = EnrolmentRequest.builder().code("0007").name("pc")
            .machineType(MachineType.WINDOWS_CLIENT).build();
        assertThat(FleetNudge.devicesWaiting(List.of(ruten, pc))).satisfiesExactly(
            n -> {
                assertThat(n.kind()).isEqualTo(FleetNudge.Kind.DEVICE_WAITING);
                assertThat(n.title()).isEqualTo("Ruten wants to join");
                assertThat(n.evidence()).isEqualTo("A phone, showing the code 4821");
                assertThat(n.value()).isEqualTo("4821");
            },
            n -> assertThat(n.evidence()).isEqualTo("A computer, showing the code 0007"));

        Machine nas = lanServer("NAS", "192.168.3.3", DeviceCategory.NAS);
        Machine rack = lanServer("Rack server", "192.168.3.4", DeviceCategory.SERVER);
        DockerService stale = new DockerService("1", "pihole", "pihole/pihole", "1", List.of(), List.of(), "running",
            "sha256:a", UpdateAvailability.UPDATE_AVAILABLE);
        DockerService fresh = new DockerService("2", "grafana", "grafana/grafana", "1", List.of(), List.of(), "running",
            "sha256:b", UpdateAvailability.UP_TO_DATE);
        List<FleetNudge> updates = FleetNudge.imageUpdates(FleetSignals.builder()
            .machines(List.of(nas, rack))
            .containers(Map.of(nas.id().value(), List.of(stale, stale.toBuilder().containerName("unbound").build()),
                rack.id().value(), List.of(fresh)))
            .build());
        assertThat(updates).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(FleetNudge.Kind.IMAGE_UPDATES);
            assertThat(n.kind().isTrouble()).isFalse();   // worth knowing, never a red card
            assertThat(n.title()).isEqualTo("2 containers on NAS have a newer image");
            assertThat(n.evidence()).isEqualTo("pihole, unbound");
            assertThat(n.value()).isEqualTo(nas.id().value());
        });
    }
}
