package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireguardClientComposeTest {

    @Test
    void standalone_usesBridgeNetworkingAndInContainerSysctls() {
        String block = WireguardClientCompose.standalone();

        assertThat(block).contains("image: " + WireguardClientImage.EXPECTED);
        assertThat(block).contains("- ./wireguard-client/config:/config");
        assertThat(block).contains("sysctls:");
        assertThat(block).contains("- net.ipv4.conf.all.src_valid_mark=1");
        assertThat(block).doesNotContain("network_mode: host");
    }

    @Test
    void hostNetwork_usesHostNetworkingAndTheWgConfsMount() {
        String block = WireguardClientCompose.hostNetwork();

        assertThat(block).contains("image: " + WireguardClientImage.EXPECTED);
        assertThat(block).contains("- ./wireguard-client/config/wg_confs:/config/wg_confs");
        assertThat(block).contains("network_mode: host");
        // Host network mode cannot use container sysctls — the script sets src_valid_mark on the host.
        assertThat(block).doesNotContain("sysctls:");
    }

    @Test
    void bothShareTheCommonSkeleton() {
        for (String block : new String[] { WireguardClientCompose.standalone(),
                                           WireguardClientCompose.hostNetwork() }) {
            assertThat(block).contains("container_name: wireguard-client");
            assertThat(block).contains("- NET_ADMIN");
            assertThat(block).contains("- SYS_MODULE");
            assertThat(block).contains("- PUID=1000");
            assertThat(block).contains("- /lib/modules:/lib/modules:ro");
            assertThat(block).contains("restart: unless-stopped");
        }
    }
}
