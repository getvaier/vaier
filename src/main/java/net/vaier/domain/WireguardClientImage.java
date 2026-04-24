package net.vaier.domain;

/**
 * Canonical wireguard image that Vaier expects peers to run. Both the
 * server's docker-compose.yml and the generated client compose must pin
 * this exact tag. Peers running a different tag are flagged as outdated
 * so the operator knows to re-download the client compose.
 */
public final class WireguardClientImage {

    public static final String EXPECTED = "lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110";

    private static final String IMAGE_NAMESPACE = "lscr.io/linuxserver/wireguard:";

    private WireguardClientImage() {}

    public static boolean isWireguardImage(String image) {
        return image != null && image.startsWith(IMAGE_NAMESPACE);
    }

    public static boolean matchesExpected(String image) {
        return EXPECTED.equals(image);
    }
}
