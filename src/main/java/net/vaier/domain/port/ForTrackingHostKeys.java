package net.vaier.domain.port;

import java.util.Optional;

/**
 * Driven port for Vaier's trust-on-first-use store of SSH host-key fingerprints, keyed by machine
 * name. The first successful connect pins a machine's fingerprint; later connects compare against it
 * and refuse a mismatch. Clearing a pin lets a legitimately rebuilt host be re-pinned.
 */
public interface ForTrackingHostKeys {

    /** The pinned host-key fingerprint for {@code machineName}, or empty when none is pinned. */
    Optional<String> getFingerprint(String machineName);

    /** Pin {@code fingerprint} for {@code machineName}, replacing any previous pin. */
    void pin(String machineName, String fingerprint);

    /** Remove any pinned fingerprint for {@code machineName}; a no-op when none exists. */
    void clear(String machineName);
}
