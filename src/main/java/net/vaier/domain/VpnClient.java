package net.vaier.domain;

public record VpnClient(
    String publicKey,
    String allowedIps,
    String endpointIp,
    String endpointPort,
    String latestHandshake,
    String transferRx,
    String transferTx
) {

    private static final long HANDSHAKE_STALE_AFTER_SECONDS = 180;

    public boolean isConnected() {
        if (latestHandshake == null) return false;
        try {
            long handshake = Long.parseLong(latestHandshake);
            long now = System.currentTimeMillis() / 1000;
            return handshake > 0 && (now - handshake) < HANDSHAKE_STALE_AFTER_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean hasAllowedIpStartingWith(String ipPrefix) {
        return allowedIps != null && allowedIps.startsWith(ipPrefix);
    }
}
