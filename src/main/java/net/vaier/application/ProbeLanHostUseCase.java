package net.vaier.application;

import net.vaier.domain.LanHostProbe;

/**
 * Probe a single LAN address the operator typed while adding a machine <em>by hand</em>, so the manual
 * path can offer the same detected readout and SSH credential test that adopting a scanned host does.
 *
 * <p>Distinct from the Enterprise LAN discovery sweep ({@link ScanLanUseCase}): this inspects the one
 * host the operator already named — a targeted, non-intrusive port probe, not a fan-out across a CIDR —
 * so it is Community-available. It never throws for the ordinary "couldn't reach it" outcome: an address
 * no relay routes to, or a host that doesn't answer, comes back as {@link LanHostProbe#notReachable()},
 * and the UI simply falls back to the manual fields.
 */
public interface ProbeLanHostUseCase {

    /** Resolve what routes to {@code address}, probe that one host through it, and report the result. */
    LanHostProbe probeHost(String address);
}
