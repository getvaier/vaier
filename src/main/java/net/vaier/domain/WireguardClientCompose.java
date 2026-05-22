package net.vaier.domain;

/**
 * Generates the {@code wireguard-client} Docker Compose service block. Two deployment shapes
 * share one skeleton: the {@link #standalone()} downloadable compose (bridge networking, config
 * at {@code /config}, in-container sysctls) and the {@link #hostNetwork()} variant emitted by
 * the peer setup script (host networking so a relay's {@code wg0} lives in the host netns).
 */
public final class WireguardClientCompose {

    private WireguardClientCompose() {}

    /** The standalone downloadable client compose service block. */
    public static String standalone() {
        return serviceBlock("Europe/Oslo", "./wireguard-client/config:/config", true, false);
    }

    /** The peer-setup-script service block — host networking, config under {@code wg_confs}. */
    public static String hostNetwork() {
        return serviceBlock("${TZ:-Europe/Oslo}", "./wireguard-client/config/wg_confs:/config/wg_confs",
            false, true);
    }

    private static String serviceBlock(String tz, String configVolume,
                                       boolean inContainerSysctl, boolean hostNetwork) {
        StringBuilder b = new StringBuilder();
        b.append("services:\n");
        b.append("  wireguard-client:\n");
        b.append("    image: ").append(WireguardClientImage.EXPECTED).append("\n");
        b.append("    container_name: wireguard-client\n");
        b.append("    cap_add:\n");
        b.append("      - NET_ADMIN\n");
        b.append("      - SYS_MODULE\n");
        b.append("    environment:\n");
        b.append("      - PUID=1000\n");
        b.append("      - PGID=1000\n");
        b.append("      - TZ=").append(tz).append("\n");
        b.append("    volumes:\n");
        b.append("      - ").append(configVolume).append("\n");
        b.append("      - /lib/modules:/lib/modules:ro\n");
        if (inContainerSysctl) {
            b.append("    sysctls:\n");
            b.append("      - net.ipv4.conf.all.src_valid_mark=1\n");
        }
        b.append("    restart: unless-stopped\n");
        if (hostNetwork) {
            b.append("    network_mode: host\n");
        }
        return b.toString();
    }
}
