package net.vaier.application;

import net.vaier.domain.Bundle;
import net.vaier.domain.MachineId;

import java.util.List;

/**
 * Offer files on one machine as a <b>Bundle</b> — a download card in Ask (#360). Every path is stat'd
 * now, so a path that is not there is refused now and never at download time; nothing is copied or written.
 *
 * <p>Throws {@code IllegalArgumentException} when a path is not absolute or climbs, {@code NotFoundException}
 * naming the first path that is not on the machine, and {@code NoHostCredentialException} or the domain SSH
 * exceptions when the machine cannot be reached.
 */
public interface OfferBundleUseCase {

    Bundle offer(MachineId machineId, String machineLabel, List<String> paths, String name);
}
