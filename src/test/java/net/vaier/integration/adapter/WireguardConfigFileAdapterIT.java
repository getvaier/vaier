package net.vaier.integration.adapter;

import net.vaier.adapter.driven.WireguardConfigFileAdapter;
import net.vaier.domain.PeerType;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WireguardConfigFileAdapter against a real temp directory.
 * Uses ReflectionTestUtils to inject the path since the adapter uses @Value injection.
 */
class WireguardConfigFileAdapterIT {

    @TempDir
    Path tempDir;

    WireguardConfigFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WireguardConfigFileAdapter();
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", tempDir.toString());
    }

    private void writePeerConfig(String peerName, String ipAddress, PeerType peerType, String lanCidr) throws IOException {
        Path peerDir = tempDir.resolve(peerName);
        Files.createDirectories(peerDir);
        StringBuilder content = new StringBuilder();
        content.append("[Interface]\n");
        content.append("Address = ").append(ipAddress).append("/32\n");
        content.append("PrivateKey = somePrivateKey\n");
        if (peerType != null || lanCidr != null) {
            content.append("# VAIER: {");
            if (peerType != null) content.append("\"peerType\":\"").append(peerType.name()).append("\"");
            if (peerType != null && lanCidr != null) content.append(",");
            if (lanCidr != null) content.append("\"lanCidr\":\"").append(lanCidr).append("\"");
            content.append("}\n");
        }
        content.append("[Peer]\n");
        content.append("PublicKey = serverPublicKey\n");
        Files.writeString(peerDir.resolve(peerName + ".conf"), content.toString());
    }

    @Test
    void getAllPeerConfigs_returnsAllThreePeers() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);
        writePeerConfig("peer2", "10.13.13.3", PeerType.MOBILE_CLIENT, null);
        writePeerConfig("peer3", "10.13.13.4", PeerType.UBUNTU_SERVER, null);

        List<PeerConfiguration> configs = adapter.getAllPeerConfigs();

        assertThat(configs).hasSize(3);
        assertThat(configs).extracting(PeerConfiguration::name)
                           .containsExactlyInAnyOrder("peer1", "peer2", "peer3");
    }

    @Test
    void wgConfsDirectory_isExcludedFromListing() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);
        Files.createDirectories(tempDir.resolve("wg_confs"));

        List<PeerConfiguration> configs = adapter.getAllPeerConfigs();

        assertThat(configs).hasSize(1);
    }

    @Test
    void hiddenDirectory_isExcludedFromListing() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);
        Files.createDirectories(tempDir.resolve(".hidden"));

        List<PeerConfiguration> configs = adapter.getAllPeerConfigs();

        assertThat(configs).hasSize(1);
    }

    @Test
    void getPeerConfigByName_returnsCorrectConfig() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("peer1");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("peer1");
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.2");
        assertThat(result.get().peerType()).isEqualTo(PeerType.UBUNTU_SERVER);
    }

    @Test
    void getPeerConfigByName_returnsEmptyWhenNotFound() {
        assertThat(adapter.getPeerConfigByName("nonexistent")).isEmpty();
    }

    @Test
    void getPeerConfigByIp_findsCorrectPeerFromMultiple() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);
        writePeerConfig("peer2", "10.13.13.3", PeerType.MOBILE_CLIENT, null);
        writePeerConfig("peer3", "10.13.13.4", PeerType.UBUNTU_SERVER, null);

        Optional<PeerConfiguration> result = adapter.getPeerConfigByIp("10.13.13.3");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("peer2");
        assertThat(result.get().peerType()).isEqualTo(PeerType.MOBILE_CLIENT);
    }

    @Test
    void vaierMetadata_peerTypeAndLanCidrParsedCorrectly() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.MOBILE_CLIENT, "192.168.1.0/24");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("peer1");

        assertThat(result).isPresent();
        assertThat(result.get().peerType()).isEqualTo(PeerType.MOBILE_CLIENT);
        assertThat(result.get().lanCidr()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void resolvePeerNameByIp_returnsNameWhenFound() throws IOException {
        writePeerConfig("myserver", "10.13.13.5", PeerType.UBUNTU_SERVER, null);

        String resolved = adapter.resolvePeerNameByIp("10.13.13.5");

        assertThat(resolved).isEqualTo("myserver");
    }

    @Test
    void resolvePeerNameByIp_returnsIpWhenNotFound() {
        String resolved = adapter.resolvePeerNameByIp("10.99.99.99");

        assertThat(resolved).isEqualTo("10.99.99.99");
    }

    @Test
    void getAllPeerConfigs_returnsEmptyWhenDirectoryIsEmpty() {
        assertThat(adapter.getAllPeerConfigs()).isEmpty();
    }

    @Test
    void configContent_containsOriginalFileContent() throws IOException {
        writePeerConfig("peer1", "10.13.13.2", PeerType.UBUNTU_SERVER, null);

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("peer1");

        assertThat(result).isPresent();
        assertThat(result.get().configContent()).contains("[Interface]");
        assertThat(result.get().configContent()).contains("10.13.13.2");
    }
}
