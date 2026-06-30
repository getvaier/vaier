package net.vaier.domain;

/**
 * A freshly seen social-login identity that has just landed in the {@link Role#PENDING} role,
 * awaiting an admin's approval. Owns its own rendering into the admin access-request email — the
 * notification service only sequences the SMTP send. Mirrors {@link PeerSnapshot}.
 */
public record PendingIdentity(String email) {

    /** Subject line for the admin access-request email. */
    public String notificationSubject() {
        return "[Vaier] New access request awaiting approval";
    }

    /**
     * Body for the admin access-request email. {@code baseDomain} builds the link to the Users
     * &rarr; Access page; when it is null or blank the link is omitted.
     */
    public String notificationBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(email)
            .append(" has signed in for the first time and is awaiting approval.\n");
        body.append("Until an admin approves it, this identity cannot reach Vaier or any published service.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nApprove or deny it on the Users → Access page: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/admin.html#users\n");
        }
        return body.toString();
    }
}
