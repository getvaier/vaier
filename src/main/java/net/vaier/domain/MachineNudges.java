package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-domain assembler for a machine's progressive-adoption nudges. It composes the applicable
 * {@link MachineNudge}s from the per-kind factories — each factory owns the "should this fire?"
 * decision from already-cached signals, and returns {@link java.util.Optional#empty()} when its
 * nudge does not apply. This class holds no decision of its own; it only gathers and orders.
 *
 * <p>The signals are gathered by the driving edge (the machines controller composes them from the
 * relevant {@code *UseCase}s) and handed in here — so no application service reaches across domains
 * to collect nudges, and no service implements a driven port to expose them. See CLAUDE.md,
 * "Cross-domain reads are different from cross-domain writes".
 */
public final class MachineNudges {

    private MachineNudges() {
    }

    /**
     * The nudges that apply to {@code machine}, trouble first (no-default-route, containers gone) and then
     * the invitations in a stable order (publish, back-up, designate-backup-server, back-up-as-root,
     * route-LAN). Each is included only when its factory says so.
     *
     * <p>The machine's backup job arrives as the job itself rather than as a pre-computed
     * "already protected" flag: whether a machine is protected <em>is</em> whether it has a job, and that is
     * a domain reading, not arithmetic for a controller to do on the way in. The same job then answers the
     * back-up-as-root question, so the driving edge fetches it once and decides nothing.
     *
     * <p>The signals arrive as a {@link MachineSignals} rather than as a parameter list — see that record
     * for why. What they are and where they come from has not changed.
     */
    public static List<MachineNudge> forMachine(Machine machine, MachineSignals signals) {
        List<MachineNudge> nudges = new ArrayList<>();
        // #357 leads: it is the only card that is trouble rather than an invitation, and every invitation
        // below it is worth less on a machine that cannot reach the internet.
        MachineNudge.noDefaultRoute(machine.name(), signals.networks()).ifPresent(nudges::add);
        // #356 sits with it, for the same reason: a service that is simply gone is not an invitation.
        nudges.addAll(MachineNudge.containersGone(machine.name(), signals.containerStandings(), signals.zone()));
        MachineNudge.publish(machine.name(), signals.publishableCount()).ifPresent(nudges::add);
        MachineNudge.backUp(machine.name(), signals.reachable(), signals.hasCredential(),
            signals.job().isPresent()).ifPresent(nudges::add);
        MachineNudge.designateBackupServer(machine, signals.fleet()).ifPresent(nudges::add);
        MachineNudge.backUpAsRoot(machine.name(), signals.latestRun(), signals.job()).ifPresent(nudges::add);
        MachineNudge.routeLan(machine, signals.networks(), signals.routingHostNetworks())
            .ifPresent(nudges::add);
        return List.copyOf(nudges);
    }
}
