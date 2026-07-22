package net.vaier.domain.port;

/**
 * Driven port for the low-level "email every admin" primitive. Resolving the admin recipients,
 * gating on SMTP being fully configured, and swallowing send failures used to be a private method on
 * {@code NotificationService}; a {@code *Service} must not implement a driven ({@code For*}) port, so
 * the primitive moved to an adapter. {@code NotificationService} composes each alert's subject/body
 * from the domain and hands them here; the {@link ForNotifyingAdmins} alert is built on the same
 * primitive.
 */
public interface ForSendingAdminNotification {

    /**
     * Send {@code subject}/{@code body} to every admin with an email, if SMTP is fully configured.
     * A no-op (logged against {@code context}) when SMTP is not configured, the password is not
     * stored, or no admin has an email. Never throws — a send failure is logged, not propagated, so
     * the scheduler and the forward-auth hot path keep running.
     */
    void sendToAdmins(String subject, String body, String context);
}
