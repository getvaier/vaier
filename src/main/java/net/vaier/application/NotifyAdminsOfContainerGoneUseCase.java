package net.vaier.application;

import net.vaier.domain.MachineContainerStanding;

/**
 * Tell admins that a container which was running on a machine is not running any more — or that it is
 * running again (#356). Driven by the state-refresh rounds, on a transition only: a container that has
 * been down for a week is not mentioned again, and a fleet where everything that was up is still up
 * produces no mail at all.
 */
public interface NotifyAdminsOfContainerGoneUseCase {

    /**
     * Alert admins that {@code standing}'s container has stopped running on {@code machineName}.
     *
     * @param standing the verdict the mail is made of — when it was last seen running, when it stopped,
     *                 and whether a reboot explains it
     */
    void notifyAdminsOfContainerGone(String machineName, MachineContainerStanding standing);

    /** Tell admins the container is running again, so a standing worry can be put down. */
    void notifyAdminsOfContainerBack(String machineName, MachineContainerStanding standing);
}
