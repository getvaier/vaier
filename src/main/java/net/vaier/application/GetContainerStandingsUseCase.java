package net.vaier.application;

import net.vaier.domain.MachineContainerStanding;

import java.util.List;

/**
 * Where every container Vaier has watched run currently stands (#356) — the fleet in one read, so the
 * Explorer can mark a container GONE rather than merely stopped without asking a single machine anything.
 *
 * <p>A container Vaier has never seen running is simply absent, which is what keeps this quiet about the
 * many containers that are stopped on purpose.
 */
public interface GetContainerStandingsUseCase {

    /** Every container standing Vaier holds, across the fleet. */
    List<MachineContainerStanding> getContainerStandings();
}
