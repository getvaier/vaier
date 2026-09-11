package net.vaier.application;

import net.vaier.domain.MachineContainerStanding;

/**
 * Tell admins that a container which was running well on a machine is in trouble — stopped, unhealthy, or
 * restart-looping (#356, #317) — or that it is running well again. Driven by the state-refresh rounds, on
 * a transition only: a container that has been down for a week is not mentioned again, and a fleet where
 * everything that was up is still up produces no mail at all.
 *
 * <p>One use case for the three troubles rather than three, because they are one conversation: the same
 * container, the same memory, and a container that moves from one to another must produce one mail or
 * none, never two views of the same event. The standing itself carries which trouble it is and the words
 * for it.
 */
public interface NotifyAdminsOfContainerTroubleUseCase {

    /**
     * Alert admins about {@code standing}'s container on {@code machineName}.
     *
     * @param standing the verdict the mail is made of — which trouble it is, when the container was last
     *                 seen well, when the trouble started, and whether a reboot explains it
     */
    void notifyAdminsOfContainerTrouble(String machineName, MachineContainerStanding standing);

    /** Tell admins the container is running well again, so a standing worry can be put down. */
    void notifyAdminsOfContainerBack(String machineName, MachineContainerStanding standing);
}
