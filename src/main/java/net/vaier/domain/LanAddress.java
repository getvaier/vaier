package net.vaier.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A machine's address on its own LAN: a dotted-quad IPv4 literal, or nothing.
 *
 * <p>Strict on purpose, and never resolved. The local path a launchpad tile takes on the home network is
 * {@code http://<lanAddress>:<port>}, which survives HSTS only because a browser never notes an IP literal as
 * an HSTS host (RFC 6797 §8.1.1); a hostname under the base domain would break for the whole max-age with no
 * server-side fix. Resolving with {@code InetAddress} would also mean a DNS lookup on a request path.
 */
public final class LanAddress {

    private static final Pattern DOTTED_QUAD =
        Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private LanAddress() {}

    /** Accepts a dotted quad or a blank (a clear); anything else is refused with the value named. */
    public static void validate(String lanAddress) {
        if (lanAddress == null || lanAddress.isBlank()) {
            return;
        }
        Matcher m = DOTTED_QUAD.matcher(lanAddress);
        if (!m.matches()) {
            throw new IllegalArgumentException("lanAddress must be a valid IPv4 address (was " + lanAddress + ")");
        }
        for (int i = 1; i <= 4; i++) {
            String octet = m.group(i);
            if (Integer.parseInt(octet) > 255 || (octet.length() > 1 && octet.charAt(0) == '0')) {
                throw new IllegalArgumentException(
                    "lanAddress must be a valid IPv4 address (was " + lanAddress + ")");
            }
        }
    }
}
