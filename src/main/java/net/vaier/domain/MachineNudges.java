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
     * The nudges that apply to {@code machine}, in a stable order (publish, then back-up, then
     * designate-backup-server). Each is included only when its factory says so.
     *
     * @param publishableCount services exposed on the machine but not yet routed through Vaier
     * @param reachable        whether the machine is reachable right now (from cached signals)
     * @param hasCredential    whether Vaier already holds an SSH credential for the machine
     * @param alreadyProtected whether anything on the machine is already backed up
     * @param fleet            the fleet's backup-server posture (drives the designate nudge)
     */
    public static List<MachineNudge> forMachine(Machine machine, int publishableCount,
                                                boolean reachable, boolean hasCredential,
                                                boolean alreadyProtected, BackupFleet fleet) {
        List<MachineNudge> nudges = new ArrayList<>();
        MachineNudge.publish(machine.name(), publishableCount).ifPresent(nudges::add);
        MachineNudge.backUp(machine.name(), reachable, hasCredential, alreadyProtected).ifPresent(nudges::add);
        MachineNudge.designateBackupServer(machine, fleet).ifPresent(nudges::add);
        return List.copyOf(nudges);
    }
}
