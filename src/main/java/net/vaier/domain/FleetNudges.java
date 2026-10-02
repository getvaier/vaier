package net.vaier.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Needs you</b>, in the order the domain wants it read: trouble first, most urgent first and never capped —
 * hiding a down machine to keep a list short would be the one wrong answer — then the nudge ladder, capped so
 * it reads as guidance rather than a to-do list, and last the newer images. A fleet with nothing wrong and
 * nothing left to do gets an empty list: "you're done", not "nothing here".
 */
public final class FleetNudges {

    /** Three rungs at a time: enough to say what is next, few enough to still be advice. */
    static final int AT_MOST = 3;

    private FleetNudges() {
    }

    public static List<FleetNudge> forFleet(FleetSignals s) {
        List<FleetNudge> trouble = new ArrayList<>();
        trouble.addAll(FleetNudge.devicesWaiting(s.enrolmentRequests()));
        trouble.addAll(FleetNudge.vaierBasics(s.preFlight()));
        trouble.addAll(FleetNudge.machinesDown(s));
        trouble.addAll(FleetNudge.backups(s));
        trouble.addAll(FleetNudge.disks(s));
        trouble.addAll(FleetNudge.osSecurityUpdates(s));
        FleetNudge.routeAudit(s.routeAudit()).ifPresent(trouble::add);
        // Stable: within one kind the factories' own order (by machine name) stands.
        trouble.sort(Comparator.comparing(FleetNudge::kind));

        List<FleetNudge> rungs = new ArrayList<>();
        // A person is waiting on someone right now, so they come before every other rung.
        FleetNudge.letPeopleIn(s.accessEntries()).ifPresent(rungs::add);
        FleetNudge.addMachine(s.machines().size()).ifPresent(rungs::add);
        FleetNudge.publish(s.machines(), s.publishable(), s.publishedCount()).ifPresent(rungs::add);
        FleetNudge.designateBackupServer(s.machines(), s.fleet()).ifPresent(rungs::add);
        FleetNudge.writeSurvivalKit(s.repositoryCount(), s.survivalKitWritten()).ifPresent(rungs::add);
        FleetNudge.configureSmtp(s.smtpConfigured()).ifPresent(rungs::add);

        List<FleetNudge> all = new ArrayList<>(trouble);
        all.addAll(rungs.subList(0, Math.min(AT_MOST, rungs.size())));
        all.addAll(FleetNudge.imageUpdates(s));
        return List.copyOf(all);
    }
}
