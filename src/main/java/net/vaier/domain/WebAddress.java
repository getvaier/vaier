package net.vaier.domain;

import net.vaier.domain.port.ForReadingWebPages;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * A <b>Web address</b> (#360): one page on the public internet that Marvin may read, and the rule that keeps
 * it to the public internet.
 *
 * <p>"Marvin reads only the public internet" is the promise the <b>web read</b> makes; this is the mechanism
 * that keeps it. A tool the model drives can be handed any address at all — by the operator, by a search
 * result, by a page that redirects — and every private address the fleet has is one hop away: the tunnel's
 * {@code 10.13.13.0/24}, the houses' LANs, the Docker API on a peer, and the cloud's own metadata address at
 * {@code 169.254.169.254}, which this box sits next to. So the judgement is here, on the value, and it is
 * taken twice: once on the address as written, and again on every address a lookup turned it into.
 *
 * <p>{@link #isPublic} is a list of what is <em>not</em> the public internet, which is the only way round it
 * can be written — there is no list of every public address. An address that resolves to nothing is refused
 * too, but said differently: a name Vaier could not look up is a name that did not work, not a name that is
 * forbidden.
 */
public record WebAddress(String value) {

    /** Long enough for any real page; a longer one is a payload wearing an address. */
    public static final int MAX_CHARS = 2048;

    private static final String FULL_ADDRESS = "Give the page's full address, beginning http:// or https://.";

    public WebAddress {
        value = value == null ? "" : value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Say which page to read.");
        }
        if (value.length() > MAX_CHARS) {
            throw new IllegalArgumentException("That address is longer than " + MAX_CHARS
                + " characters; Marvin will not follow it.");
        }
        URI uri = parse(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.isEmpty()) {
            throw new IllegalArgumentException(FULL_ADDRESS);
        }
        // The scheme is read before the host: file:/// and javascript: have no host either, and "not file"
        // says far more than "give a full address".
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Marvin reads only http and https addresses, not " + scheme + ".");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(FULL_ADDRESS);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "An address with a user name in it is refused; give the page's plain address.");
        }
        refuseAPrivateLiteral(uri.getHost());
    }

    public static WebAddress of(String url) {
        return new WebAddress(url);
    }

    public String host() {
        return parse(value).getHost();
    }

    public URI uri() {
        return parse(value);
    }

    /** Where a {@code Location} header points, judged by the same rules — relative headers and all. */
    public WebAddress redirectedTo(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("That page redirected somewhere it did not say.");
        }
        return new WebAddress(uri().resolve(location.trim()).toString());
    }

    /** The domain asks the port; the service only hands it in. */
    public WebPage read(ForReadingWebPages webPages) {
        return webPages.read(this);
    }

    /**
     * What every lookup answered, judged before anything is connected. Empty means the name did not resolve,
     * which is said as such: unknown is not the same as forbidden.
     */
    public void requirePublic(List<InetAddress> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalArgumentException("Vaier could not look up " + host()
                + ", so it did not try to reach it.");
        }
        for (InetAddress address : resolved) {
            if (!isPublic(address)) {
                throw new IllegalArgumentException(notPublic(host()));
            }
        }
    }

    /**
     * Whether one address is on the public internet: everything that is left after loopback, the unspecified
     * range, link-local, the private ranges, carrier-grade NAT, multicast, broadcast and IPv6's own private
     * range are taken out. An IPv4 address wearing an IPv6 hat is judged by its IPv4 part.
     */
    public static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            return isPublic(ipv4Of(bytes));
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            boolean zeroRange = first == 0;
            boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127;
            boolean broadcast = first == 255 && second == 255 && (bytes[2] & 0xFF) == 255 && (bytes[3] & 0xFF) == 255;
            return !zeroRange && !carrierGradeNat && !broadcast;
        }
        return (bytes[0] & 0xFE) != 0xFC;   // fc00::/7, IPv6's own private range
    }

    // --- reading the address ----------------------------------------------------------------------------

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(FULL_ADDRESS);
        }
    }

    /**
     * An address written out as numbers needs no lookup, so it is judged here and now. Only a word that is
     * already shaped like an address is handed to {@code InetAddress}, which would otherwise ask DNS.
     */
    private static void refuseAPrivateLiteral(String host) {
        boolean looksLikeIpv4 = host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean looksLikeIpv6 = host.startsWith("[") || host.indexOf(':') >= 0;
        if (!looksLikeIpv4 && !looksLikeIpv6) {
            return;
        }
        try {
            if (!isPublic(InetAddress.getByName(host))) {
                throw new IllegalArgumentException(notPublic(host));
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(FULL_ADDRESS);
        }
    }

    private static String notPublic(String host) {
        return "Marvin reads only the public internet; " + host + " is not on it.";
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static InetAddress ipv4Of(byte[] bytes) {
        try {
            return InetAddress.getByAddress(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Four bytes are always an address", e);
        }
    }
}
