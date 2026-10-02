package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * The operator says a server is switched off on purpose: while it is, its silence is not trouble — no row in
 * Needs you, no mail, no scheduled backup. Refused for a personal device and for the Vaier server.
 */
public interface MarkSwitchedOffUseCase {

    void markSwitchedOff(MachineId machineId);
}
