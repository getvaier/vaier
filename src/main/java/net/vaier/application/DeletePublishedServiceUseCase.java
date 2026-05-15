package net.vaier.application;

public interface DeletePublishedServiceUseCase {

    /**
     * Delete a published service. {@code pathPrefix} is optional; null means the host-only route
     * (the legacy one-service-per-host case). Sibling routes on the same FQDN keep the CNAME alive
     * — DNS is only deleted when the last route on a host is removed.
     */
    void deleteService(String fqdn, String pathPrefix);

    /** Convenience overload for the common host-only case (no pathPrefix). */
    default void deleteService(String fqdn) {
        deleteService(fqdn, null);
    }
}
