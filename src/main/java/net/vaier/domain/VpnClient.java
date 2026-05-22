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
        long handshake = latestHandshakeEpoch();
        long now = System.currentTimeMillis() / 1000;
        return handshake > 0 && (now - handshake) < HANDSHAKE_STALE_AFTER_SECONDS;
    }

    /** The last-handshake instant as a Unix epoch second; {@code 0} when absent or unparseable. */
    public long latestHandshakeEpoch() {
        if (latestHandshake == null) return 0L;
        try {
            return Long.parseLong(latestHandshake.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * This peer's WireGuard tunnel IP — the first {@code allowedIps} entry with its mask
     * stripped. For a relay peer whose {@code allowedIps} also lists a LAN CIDR, the tunnel
     * IP is always the first comma-separated entry. {@code null} when {@code allowedIps} is absent.
     */
    public String vpnIp() {
        if (allowedIps == null) return null;
        String first = allowedIps.split(",")[0].trim();
        int slash = first.indexOf('/');
        return slash < 0 ? first : first.substring(0, slash);
    }

    /**
     * Whether {@code address} falls within any of this peer's {@code allowedIps} entries.
     * Each entry is parsed as a {@link Cidr}; a bare IP is treated as a {@code /32}.
     */
    public boolean containsAddress(String address) {
        if (allowedIps == null || address == null || address.isBlank()) return false;
        String target = address.trim();
        for (String raw : allowedIps.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            String cidr = entry.contains("/") ? entry : entry + "/32";
            try {
                if (Cidr.parse(cidr).contains(target)) return true;
            } catch (IllegalArgumentException e) {
                // malformed allowedIps entry — skip it
            }
        }
        return false;
    }
}
