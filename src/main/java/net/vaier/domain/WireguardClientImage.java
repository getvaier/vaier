package net.vaier.domain;

import java.util.List;

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

    /**
     * Whether any container in {@code containers} runs a WireGuard image other than the
     * {@link #EXPECTED} tag — i.e. the peer should re-download its client compose.
     */
    public static boolean anyOutdated(List<DockerService> containers) {
        return containers.stream()
            .map(DockerService::image)
            .filter(WireguardClientImage::isWireguardImage)
            .anyMatch(image -> !matchesExpected(image));
    }
}
