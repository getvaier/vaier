package net.vaier.application;

import net.vaier.domain.MachineNetworks;

/**
 * Tell admins that a machine has lost — or regained — its <b>default route</b> (#357). Driven by the
 * fleet's five-minute rounds, on a transition only: a machine that has been in this state for a week is
 * not mentioned again, and a healthy fleet produces no mail at all.
 */
public interface NotifyAdminsOfMissingDefaultRouteUseCase {

    /**
     * Alert admins that {@code machineName} holds no default route and so cannot reach the internet.
     *
     * @param networks the reading the verdict came from — the evidence the mail carries
     */
    void notifyAdminsOfMissingDefaultRoute(String machineName, MachineNetworks networks);

    /**
     * Tell admins {@code machineName} can reach the internet again.
     *
     * @param networks the fresh reading, so the all-clear describes what is true now rather than repeating
     *                 the warning it is cancelling
     */
    void notifyAdminsOfDefaultRouteRestored(String machineName, MachineNetworks networks);
}
