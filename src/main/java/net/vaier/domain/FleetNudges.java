package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The fleet nudge ladder, in the order the domain wants it read and capped so it reads as guidance rather than
 * a to-do list. A finished fleet gets an empty list — "you're done", not "nothing here".
 */
public final class FleetNudges {

    /** Three at a time: enough to say what is next, few enough to still be advice. */
    static final int AT_MOST = 3;

    private FleetNudges() {
    }

    public static List<FleetNudge> forFleet(FleetSignals s) {
        List<FleetNudge> rungs = new ArrayList<>();
        // A person is waiting on someone right now, so they come before every rung of the ladder.
        FleetNudge.letPeopleIn(s.accessEntries()).ifPresent(rungs::add);
        FleetNudge.addMachine(s.machines().size()).ifPresent(rungs::add);
        FleetNudge.publish(s.machines(), s.publishable(), s.publishedCount()).ifPresent(rungs::add);
        FleetNudge.designateBackupServer(s.machines(), s.fleet()).ifPresent(rungs::add);
        FleetNudge.writeSurvivalKit(s.repositoryCount(), s.survivalKitWritten()).ifPresent(rungs::add);
        FleetNudge.configureSmtp(s.smtpConfigured()).ifPresent(rungs::add);
        return List.copyOf(rungs.subList(0, Math.min(AT_MOST, rungs.size())));
    }
}
