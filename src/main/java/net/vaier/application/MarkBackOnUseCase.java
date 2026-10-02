package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * A machine switched off on purpose is back on — said by the operator, or noticed by Vaier the first time it
 * reaches the machine again. Monitoring and backups resume as before. A machine never marked is left alone.
 */
public interface MarkBackOnUseCase {

    void markBackOn(MachineId machineId);
}
