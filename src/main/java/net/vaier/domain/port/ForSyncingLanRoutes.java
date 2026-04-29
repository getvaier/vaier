package net.vaier.domain.port;

import java.util.Set;

public interface ForSyncingLanRoutes {

    /**
     * Reconciles LAN-CIDR routes inside the current network namespace so each desired CIDR
     * is reachable via the WireGuard container, and any previously installed CIDR no longer
     * desired is removed. Idempotent.
     */
    void syncLanRoutes(Set<String> desiredCidrs);
}
