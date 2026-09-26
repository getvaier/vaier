package net.vaier.application;

import net.vaier.domain.ConflictException;
import net.vaier.domain.MachineId;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.OsUpgrade;

import java.util.concurrent.CompletionStage;

/**
 * Install a machine's pending OS updates — an <b>OS upgrade</b>. Accepted rather than performed: the call
 * returns once the machine is judged, and the minutes of apt or dnf run in the background. The settlement
 * arrives on the fleet's SSE stream, a failure is mailed, and the returned stage completes with it.
 */
public interface UpgradeOsUseCase {

    /**
     * @throws ConflictException        where Vaier cannot get root, or finds neither apt nor dnf
     * @throws NoHostCredentialException when Vaier holds no SSH credential for the machine
     */
    CompletionStage<OsUpgrade.Settlement> upgradeOs(MachineId machineId);
}
