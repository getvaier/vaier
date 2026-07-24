package net.vaier.domain.port;

import net.vaier.domain.MachineId;

import java.util.Optional;

/**
 * Driven port for Vaier's trust-on-first-use store of SSH host-key fingerprints, keyed by {@link MachineId}. The first successful connect pins a machine's fingerprint; later connects compare against it
 * and refuse a mismatch. Clearing a pin lets a legitimately rebuilt host be re-pinned.
 */
public interface ForTrackingHostKeys {

    /** The pinned host-key fingerprint for {@code machineId}, or empty when none is pinned. */
    Optional<String> getFingerprint(MachineId machineId);

    /** Pin {@code fingerprint} for {@code machineId}, replacing any previous pin. */
    void pin(MachineId machineId, String fingerprint);

    /** Remove any pinned fingerprint for {@code machineId}; a no-op when none exists. */
    void clear(MachineId machineId);
}
