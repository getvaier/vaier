package net.vaier.adapter.driven;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WireguardConfigFileAdapterTest {

    @TempDir Path configDir;

    WireguardConfigFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WireguardConfigFileAdapter();
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", configDir.toString());
    }

    // --- getPeerConfigByName ---

    @Test
    void getPeerConfigByName_returnsConfigWhenPeerExists() throws IOException {
        createPeerConf("laptop", "10.13.13.2");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("laptop");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("laptop");
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.2");
    }

    @Test
    void getPeerConfigByName_returnsEmptyWhenPeerDoesNotExist() {
        assertThat(adapter.getPeerConfigByName("nonexistent")).isEmpty();
    }

    @Test
    void getPeerConfigByName_parsesIpWithCidrNotation() throws IOException {
        createPeerConf("server1", "10.13.13.5");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("server1");

        assertThat(result).isPresent();
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.5");
    }

    @Test
    void getPeerConfigByName_handlesExtraWhitespaceAroundEquals() throws IOException {
        Path peerDir = configDir.resolve("laptop");
        Files.createDirectories(peerDir);
        Files.writeString(peerDir.resolve("laptop.conf"),
                "[Interface]\nAddress = 10.13.13.3/32\nPrivateKey = abc123\n");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("laptop");

        assertThat(result).isPresent();
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.3");
    }

    // --- getPeerConfigByIp ---

    @Test
    void getPeerConfigByIp_findsPeerMatchingIp() throws IOException {
        createPeerConf("laptop", "10.13.13.2");
        createPeerConf("phone", "10.13.13.3");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByIp("10.13.13.3");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("phone");
    }

    @Test
    void getPeerConfigByIp_returnsEmptyWhenNoMatchFound() throws IOException {
        createPeerConf("laptop", "10.13.13.2");

        assertThat(adapter.getPeerConfigByIp("10.13.13.99")).isEmpty();
    }

    @Test
    void getPeerConfigByIp_returnsEmptyWhenConfigDirMissing() {
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", "/nonexistent/path");

        assertThat(adapter.getPeerConfigByIp("10.13.13.2")).isEmpty();
    }

    @Test
    void getPeerConfigByIp_ignoresWgConfsDirectory() throws IOException {
        createPeerConf("laptop", "10.13.13.2");
        Path wgConfsDir = configDir.resolve("wg_confs");
        Files.createDirectories(wgConfsDir);

        Optional<PeerConfiguration> result = adapter.getPeerConfigByIp("10.13.13.2");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("laptop");
    }

    // --- resolvePeerNameByIp ---

    @Test
    void resolvePeerNameByIp_returnsPeerNameWhenFound() throws IOException {
        createPeerConf("my-server", "10.13.13.4");

        assertThat(adapter.resolvePeerNameByIp("10.13.13.4")).isEqualTo("my-server");
    }

    @Test
    void resolvePeerNameByIp_returnsIpWhenNoPeerFound() throws IOException {
        createPeerConf("laptop", "10.13.13.2");

        assertThat(adapter.resolvePeerNameByIp("10.13.13.99")).isEqualTo("10.13.13.99");
    }

    @Test
    void resolvePeerNameByIp_returnsIpWhenConfigDirMissing() {
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", "/nonexistent/path");

        assertThat(adapter.resolvePeerNameByIp("10.13.13.2")).isEqualTo("10.13.13.2");
    }

    // --- VAIER metadata (peerType / lanCidr) ---

    @Test
    void getPeerConfigByName_defaultsToUbuntuServerWhenNoVaierComment() throws IOException {
        createPeerConf("server1", "10.13.13.2");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("server1");

        assertThat(result).isPresent();
        assertThat(result.get().peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
        assertThat(result.get().lanCidr()).isNull();
    }

    @Test
    void getPeerConfigByName_parsesMobileClientFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("phone", "10.13.13.3",
                "{\"peerType\":\"MOBILE_CLIENT\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("phone");

        assertThat(result).isPresent();
        assertThat(result.get().peerType()).isEqualTo(net.vaier.domain.MachineType.MOBILE_CLIENT);
        assertThat(result.get().lanCidr()).isNull();
    }

    @Test
    void getPeerConfigByName_parsesUbuntuServerWithLanCidrFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("spain", "10.13.13.4",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.1.0/24\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("spain");

        assertThat(result).isPresent();
        assertThat(result.get().peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
        assertThat(result.get().lanCidr()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void getPeerConfigByName_parsesLanAddressFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanAddress\":\"192.168.3.121\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("apalveien5");

        assertThat(result).isPresent();
        assertThat(result.get().lanAddress()).isEqualTo("192.168.3.121");
    }

    // --- updateLanAddress ---

    @Test
    void updateLanAddress_writesLanAddressIntoVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateLanAddress("apalveien5", "192.168.3.121");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateLanAddress_preservesExistingLanCidr() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\"}");

        adapter.updateLanAddress("apalveien5", "192.168.3.121");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
    }

    @Test
    void updateLanAddress_blankClearsExistingValue() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanAddress\":\"192.168.3.121\"}");

        adapter.updateLanAddress("apalveien5", "");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanAddress()).isNull();
    }

    @Test
    void updateLanAddress_addsVaierCommentWhenMissing() throws IOException {
        createPeerConf("apalveien5", "10.13.13.6");

        adapter.updateLanAddress("apalveien5", "192.168.3.121");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateLanAddress_preservesRestOfConfigFile() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateLanAddress("apalveien5", "192.168.3.121");

        String content = Files.readString(configDir.resolve("apalveien5").resolve("apalveien5.conf"));
        assertThat(content).contains("Address=10.13.13.6/32");
        assertThat(content).contains("PrivateKey=testkey");
    }

    @Test
    void updateLanAddress_throwsWhenPeerDoesNotExist() {
        assertThat(adapter.getPeerConfigByName("ghost")).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> adapter.updateLanAddress("ghost", "192.168.3.121"));
    }

    // --- updateLanCidr ---

    @Test
    void updateLanCidr_writesLanCidrIntoVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateLanCidr("apalveien5", "192.168.3.0/24");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateLanCidr_preservesExistingLanAddress() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanAddress\":\"192.168.3.121\"}");

        adapter.updateLanCidr("apalveien5", "192.168.3.0/24");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
    }

    @Test
    void updateLanCidr_blankClearsExistingValue() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\"}");

        adapter.updateLanCidr("apalveien5", "");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanCidr()).isNull();
    }

    @Test
    void updateLanCidr_addsVaierCommentWhenMissing() throws IOException {
        createPeerConf("apalveien5", "10.13.13.6");

        adapter.updateLanCidr("apalveien5", "192.168.3.0/24");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateLanCidr_throwsWhenPeerDoesNotExist() {
        assertThat(adapter.getPeerConfigByName("ghost")).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> adapter.updateLanCidr("ghost", "192.168.3.0/24"));
    }

    // --- getAllPeerConfigs ---

    @Test
    void getAllPeerConfigs_returnsEmptyListWhenNoPeers() {
        assertThat(adapter.getAllPeerConfigs()).isEmpty();
    }

    @Test
    void getAllPeerConfigs_returnsAllPeerConfigs() throws IOException {
        createPeerConf("laptop", "10.13.13.2");
        createPeerConf("phone", "10.13.13.3");

        var result = adapter.getAllPeerConfigs();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PeerConfiguration::name)
                .containsExactlyInAnyOrder("laptop", "phone");
    }

    @Test
    void getAllPeerConfigs_ignoresWgConfsDirectory() throws IOException {
        createPeerConf("laptop", "10.13.13.2");
        Files.createDirectories(configDir.resolve("wg_confs"));

        assertThat(adapter.getAllPeerConfigs()).hasSize(1);
    }

    @Test
    void getAllPeerConfigs_ignoresDotDirectories() throws IOException {
        createPeerConf("laptop", "10.13.13.2");
        Files.createDirectories(configDir.resolve(".hidden"));

        assertThat(adapter.getAllPeerConfigs()).hasSize(1);
    }

    @Test
    void getAllPeerConfigs_returnsEmptyListWhenConfigDirMissing() {
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", "/nonexistent/path");

        assertThat(adapter.getAllPeerConfigs()).isEmpty();
    }

    // helpers

    private void createPeerConf(String peerName, String ip) throws IOException {
        Path peerDir = configDir.resolve(peerName);
        Files.createDirectories(peerDir);
        Files.writeString(peerDir.resolve(peerName + ".conf"),
                "[Interface]\nAddress=" + ip + "/32\nPrivateKey=testkey\n");
    }

    private void createPeerConfWithVaierMetadata(String peerName, String ip, String vaierJson)
            throws IOException {
        Path peerDir = configDir.resolve(peerName);
        Files.createDirectories(peerDir);
        Files.writeString(peerDir.resolve(peerName + ".conf"),
                "# VAIER: " + vaierJson + "\n[Interface]\nAddress=" + ip + "/32\nPrivateKey=testkey\n");
    }
}
