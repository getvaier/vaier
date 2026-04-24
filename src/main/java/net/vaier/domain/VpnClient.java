package net.vaier.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

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

    public boolean containsAddress(String address) {
        if (allowedIps == null || address == null || address.isBlank()) return false;
        long target = parseIpv4(address.trim());
        if (target < 0) return false;
        for (String raw : allowedIps.split(",")) {
            String cidr = raw.trim();
            if (cidr.isEmpty()) continue;
            if (cidrContainsV4(cidr, target)) return true;
        }
        return false;
    }

    private static boolean cidrContainsV4(String cidr, long target) {
        int slash = cidr.indexOf('/');
        String ipPart = slash < 0 ? cidr : cidr.substring(0, slash);
        long net = parseIpv4(ipPart.trim());
        if (net < 0) return false;
        int prefix = 32;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (prefix < 0 || prefix > 32) return false;
        }
        if (prefix == 0) return true;
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (net & mask) == (target & mask);
    }

    private static long parseIpv4(String s) {
        Matcher m = IPV4.matcher(s);
        if (!m.matches()) return -1;
        long result = 0;
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(m.group(i));
            if (octet > 255) return -1;
            result = (result << 8) | octet;
        }
        return result;
    }
}
