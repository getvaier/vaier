package net.vaier.domain.port;

/**
 * Driven port for raising admin-facing notifications triggered by a domain event. Implemented by
 * the application's notification service; depended on by services that detect a notable event
 * (e.g. a new pending access entry) without taking on the SMTP machinery themselves. Mirrors how
 * {@code ForSendingNotificationEmail} keeps the wire details out of the callers.
 */
public interface ForNotifyingAdmins {

    /**
     * Notify Vaier admins that {@code email} has just landed as a new pending access entry,
     * awaiting approval. Implementations must be fire-and-forget and exception-safe — they are
     * invoked from the forward-auth hot path and must never add latency to or throw into it.
     */
    void notifyNewPendingIdentity(String email);
}
