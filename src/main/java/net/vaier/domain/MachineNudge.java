package net.vaier.domain;

import java.util.Optional;

/**
 * A single evidence-backed, yes/no suggestion Vaier surfaces on a reachable machine — a
 * progressive-adoption nudge. Each nudge asks the operator to adopt one more of Vaier's capabilities
 * for a machine Vaier already knows about, and carries everything the operator needs to make the call
 * without leaving the machine: a {@link #kind}, an operator-facing {@link #title}, the {@link #evidence}
 * (the "why" Vaier is suggesting it, drawn from already-cached state), and an {@link #action} hint
 * describing what happens on "yes".
 *
 * <p>Pure domain: the "should we suggest X?" decision for each kind is a static factory here, composed
 * from domain predicates ({@link BackupFleet#needsBackupServer()}, {@link DeviceCategory#isStorageClass()},
 * a machine's reachability, whether it is already protected). A factory returns {@link Optional#empty()}
 * when the nudge does not apply, so an emitter never has to re-decide in the application layer.
 */
public record MachineNudge(String machineName, Kind kind, String title, String evidence, String action) {

    /** The kinds of nudge Vaier can raise on a machine. */
    public enum Kind {
        /** The machine exposes services that are not yet routed through Vaier. */
        PUBLISH,
        /** The machine is a reachable, credentialed host with nothing backed up. */
        BACK_UP,
        /** The fleet has no backup server yet and this machine could host one. */
        DESIGNATE_BACKUP_SERVER
    }

    public MachineNudge {
        if (machineName == null || machineName.isBlank()) {
            throw new IllegalArgumentException("MachineNudge machineName must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("MachineNudge kind must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("MachineNudge title must not be blank");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("MachineNudge evidence must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("MachineNudge action must not be blank");
        }
    }

    /**
     * PUBLISH — suggest routing the machine's exposed services through Vaier when it has at least one
     * publishable service. The evidence names how many are exposed but unrouted; no nudge when none are.
     */
    public static Optional<MachineNudge> publish(String machineName, int publishableCount) {
        if (publishableCount <= 0) {
            return Optional.empty();
        }
        String plural = publishableCount == 1 ? "" : "s";
        return Optional.of(new MachineNudge(machineName, Kind.PUBLISH,
            "Publish " + publishableCount + " service" + plural,
            publishableCount + " service" + plural + " exposed on this machine, none routed through Vaier yet",
            "Give each an HTTPS address and a launchpad tile"));
    }

    /**
     * BACK_UP — suggest protecting the machine when it is reachable, Vaier already holds an SSH
     * credential for it, and nothing on it is protected yet. Missing any of the three ⇒ no nudge.
     * Borg readiness is deliberately not consulted here — the protect-paths flow handles it at
     * action time — so the decision rests only on cheap, already-cached signals.
     */
    public static Optional<MachineNudge> backUp(String machineName, boolean reachable,
                                                boolean hasCredential, boolean alreadyProtected) {
        if (!reachable || !hasCredential || alreadyProtected) {
            return Optional.empty();
        }
        return Optional.of(new MachineNudge(machineName, Kind.BACK_UP,
            "Back up " + machineName,
            "Reachable, Vaier holds an SSH credential, and nothing on it is backed up yet",
            "Pick folders to protect on a schedule"));
    }

    /**
     * DESIGNATE_BACKUP_SERVER — suggest making this machine the fleet's backup server, but only when the
     * fleet {@link BackupFleet#needsBackupServer() has none yet} and the machine is
     * {@link DeviceCategory#isStorageClass() storage-class} (a NAS, or a general server). Once a backup
     * server exists the fleet no longer needs one, so this returns empty.
     */
    public static Optional<MachineNudge> designateBackupServer(Machine machine, BackupFleet fleet) {
        if (!fleet.needsBackupServer() || !machine.deviceCategory().isStorageClass()) {
            return Optional.empty();
        }
        return Optional.of(new MachineNudge(machine.name(), Kind.DESIGNATE_BACKUP_SERVER,
            "Make " + machine.name() + " the backup server",
            "The fleet has no backup server yet, and this machine has storage to host one",
            "Set up borg on it so other machines can back up here"));
    }
}
