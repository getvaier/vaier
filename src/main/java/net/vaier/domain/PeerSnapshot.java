package net.vaier.domain;

import java.time.Instant;

/**
 * A point-in-time view of a VPN peer's connectivity, captured when its connected state changes.
 * Owns its own rendering into the admin transition email — the notification service only
 * sequences the SMTP send.
 */
public record PeerSnapshot(
    String name,
    MachineType peerType,
    boolean connected,
    long latestHandshakeEpochSeconds,
    String lanAddress
) {

    /** Subject line for the admin transition email. */
    public String notificationSubject() {
        return "[Vaier] " + name + " is now " + connectivityLabel();
    }

    /**
     * Body for the admin transition email. {@code baseDomain} builds the Vaier UI link; when it
     * is null or blank the link is omitted.
     */
    public String notificationBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(name).append("\n");
        body.append("Type: ").append(peerType.name()).append("\n");
        body.append("Status: ").append(connectivityLabel()).append("\n");
        if (latestHandshakeEpochSeconds > 0) {
            body.append("Last handshake: ")
                .append(Instant.ofEpochSecond(latestHandshakeEpochSeconds))
                .append("\n");
        }
        if (lanAddress != null && !lanAddress.isBlank()) {
            body.append("LAN address: ").append(lanAddress).append("\n");
        }
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/explorer.html\n");
        }
        return body.toString();
    }

    private String connectivityLabel() {
        return connected ? "connected" : "disconnected";
    }
}
