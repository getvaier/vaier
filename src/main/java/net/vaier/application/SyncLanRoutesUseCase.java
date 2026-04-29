package net.vaier.application;

public interface SyncLanRoutesUseCase {

    /**
     * Installs an {@code ip route} entry for every relay peer's {@code lanCidr} via the
     * WireGuard container, and removes any stale entries we previously installed for CIDRs
     * that are no longer configured. Called at startup and whenever a peer's lanCidr changes.
     */
    void syncLanRoutes();
}
