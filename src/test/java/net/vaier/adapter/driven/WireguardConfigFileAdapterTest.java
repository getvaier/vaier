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
        org.junit.jupiter.api.Assertions.assertThrows(net.vaier.domain.PeerNotFoundException.class,
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
        org.junit.jupiter.api.Assertions.assertThrows(net.vaier.domain.PeerNotFoundException.class,
            () -> adapter.updateLanCidr("ghost", "192.168.3.0/24"));
    }

    // --- VAIER metadata (description, #54) ---

    @Test
    void getPeerConfigByName_parsesDescriptionFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"Home media server\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("nuc");

        assertThat(result).isPresent();
        assertThat(result.get().description()).isEqualTo("Home media server");
    }

    @Test
    void getPeerConfigByName_descriptionNullWhenAbsentFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7", "{\"peerType\":\"UBUNTU_SERVER\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("nuc");

        assertThat(result).isPresent();
        assertThat(result.get().description()).isNull();
    }

    @Test
    void getPeerConfigByName_parsesDescriptionWithEscapedCharacters() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"NAS \\\"box\\\" at C:\\\\data\"}");

        Optional<PeerConfiguration> result = adapter.getPeerConfigByName("nuc");

        assertThat(result).isPresent();
        assertThat(result.get().description()).isEqualTo("NAS \"box\" at C:\\data");
    }

    @Test
    void updateDescription_writesDescriptionIntoVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7", "{\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateDescription("nuc", "Raspberry Pi in garage");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.description()).isEqualTo("Raspberry Pi in garage");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateDescription_preservesPeerTypeLanCidrAndLanAddress() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\",\"lanAddress\":\"192.168.3.121\"}");

        adapter.updateDescription("apalveien5", "Spain relay");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.description()).isEqualTo("Spain relay");
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
    }

    @Test
    void updateDescription_blankClearsExistingValue() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"old text\"}");

        adapter.updateDescription("nuc", "");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.description()).isNull();
    }

    @Test
    void updateDescription_addsVaierCommentWhenMissing() throws IOException {
        createPeerConf("nuc", "10.13.13.7");

        adapter.updateDescription("nuc", "Home media server");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.description()).isEqualTo("Home media server");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateDescription_roundTripsSpecialCharacters() throws IOException {
        createPeerConf("nuc", "10.13.13.7");

        adapter.updateDescription("nuc", "NAS \"box\" — line1\nline2");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.description()).isEqualTo("NAS \"box\" — line1\nline2");
    }

    @Test
    void updateDescription_throwsWhenPeerDoesNotExist() {
        assertThat(adapter.getPeerConfigByName("ghost")).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(net.vaier.domain.PeerNotFoundException.class,
            () -> adapter.updateDescription("ghost", "anything"));
    }

    @Test
    void updateLanAddress_preservesExistingDescription() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"keep me\"}");

        adapter.updateLanAddress("nuc", "192.168.3.121");

        assertThat(adapter.getPeerConfigByName("nuc").orElseThrow().description()).isEqualTo("keep me");
    }

    @Test
    void updateLanCidr_preservesExistingDescription() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"keep me\"}");

        adapter.updateLanCidr("nuc", "192.168.3.0/24");

        assertThat(adapter.getPeerConfigByName("nuc").orElseThrow().description()).isEqualTo("keep me");
    }

    // --- updateName: the editable display name (#209) ---

    @Test
    void updateName_setsDisplayNameInMetadata() throws IOException {
        createPeerConf("media-server", "10.13.13.2");

        adapter.updateName("media-server", "Media Server");

        PeerConfiguration result = adapter.getPeerConfigByName("media-server").orElseThrow();
        assertThat(result.id()).isEqualTo("media-server");
        assertThat(result.name()).isEqualTo("Media Server");
    }

    @Test
    void updateName_storedNameOverridesTheHumanisedIdFallback() throws IOException {
        // With no stored name a peer falls back to display(id); once set, the stored name wins —
        // even when it contains a hyphen the operator typed deliberately.
        createPeerConf("media-server", "10.13.13.2");
        assertThat(adapter.getPeerConfigByName("media-server").orElseThrow().name())
            .isEqualTo("media server");

        adapter.updateName("media-server", "Living-room NAS");

        assertThat(adapter.getPeerConfigByName("media-server").orElseThrow().name())
            .isEqualTo("Living-room NAS");
    }

    @Test
    void updateName_blankClearsNameBackToHumanisedIdFallback() throws IOException {
        createPeerConf("media-server", "10.13.13.2");
        adapter.updateName("media-server", "Media Server");

        adapter.updateName("media-server", "  ");

        assertThat(adapter.getPeerConfigByName("media-server").orElseThrow().name())
            .isEqualTo("media server");
    }

    @Test
    void updateName_preservesExistingMetadata() throws IOException {
        createPeerConfWithVaierMetadata("spain", "10.13.13.4",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.1.0/24\",\"description\":\"Spain relay\"}");

        adapter.updateName("spain", "Spain Relay");

        PeerConfiguration result = adapter.getPeerConfigByName("spain").orElseThrow();
        assertThat(result.name()).isEqualTo("Spain Relay");
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
        assertThat(result.lanCidr()).isEqualTo("192.168.1.0/24");
        assertThat(result.description()).isEqualTo("Spain relay");
    }

    @Test
    void updateName_throwsWhenPeerDoesNotExist() {
        org.junit.jupiter.api.Assertions.assertThrows(net.vaier.domain.PeerNotFoundException.class,
            () -> adapter.updateName("ghost", "Phantom"));
    }

    // --- VAIER metadata (deviceCategory override) ---

    @Test
    void getPeerConfigByName_parsesDeviceCategoryFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("nas", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}");

        PeerConfiguration result = adapter.getPeerConfigByName("nas").orElseThrow();

        assertThat(result.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
        assertThat(result.deviceCategoryOverridden()).isTrue();
    }

    @Test
    void getPeerConfigByName_deviceCategoryNullWhenAbsentFromVaierComment() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7", "{\"peerType\":\"UBUNTU_SERVER\"}");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();

        assertThat(result.deviceCategory()).isNull();
        assertThat(result.deviceCategoryOverridden()).isFalse();
    }

    @Test
    void updateDeviceCategory_writesOverrideIntoVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7", "{\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateDeviceCategory("nuc", "NAS");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
        assertThat(result.peerType()).isEqualTo(net.vaier.domain.MachineType.UBUNTU_SERVER);
    }

    @Test
    void updateDeviceCategory_preservesOtherMetadata() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\","
                + "\"lanAddress\":\"192.168.3.121\",\"description\":\"Spain relay\",\"name\":\"Spain\"}");

        adapter.updateDeviceCategory("apalveien5", "ROUTER");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.ROUTER);
        assertThat(result.lanCidr()).isEqualTo("192.168.3.0/24");
        assertThat(result.lanAddress()).isEqualTo("192.168.3.121");
        assertThat(result.description()).isEqualTo("Spain relay");
        assertThat(result.name()).isEqualTo("Spain");
    }

    @Test
    void updateDeviceCategory_blankClearsOverride() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}");

        adapter.updateDeviceCategory("nuc", "");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.deviceCategory()).isNull();
    }

    @Test
    void updateDeviceCategory_addsVaierCommentWhenMissing() throws IOException {
        createPeerConf("nuc", "10.13.13.7");

        adapter.updateDeviceCategory("nuc", "PRINTER");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.PRINTER);
    }

    @Test
    void updateDeviceCategory_throwsWhenPeerDoesNotExist() {
        org.junit.jupiter.api.Assertions.assertThrows(net.vaier.domain.PeerNotFoundException.class,
            () -> adapter.updateDeviceCategory("ghost", "NAS"));
    }

    @Test
    void updateDescription_preservesExistingDeviceCategory() throws IOException {
        createPeerConfWithVaierMetadata("nuc", "10.13.13.7",
                "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}");

        adapter.updateDescription("nuc", "keep category");

        assertThat(adapter.getPeerConfigByName("nuc").orElseThrow().deviceCategory())
            .isEqualTo(net.vaier.domain.DeviceCategory.NAS);
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

    // --- rewriteConfig (#247) ---

    @Test
    void rewriteConfig_overwritesTheEntireConfFile() throws IOException {
        createPeerConf("apalveien5", "10.13.13.6");
        String newContent = "# VAIER: {\"peerType\":\"UBUNTU_SERVER\"}\n[Interface]\n"
                + "Address=10.13.13.6/32\nPrivateKey=testkey\n[Peer]\n"
                + "AllowedIPs = 10.13.13.0/24,172.31.16.0/20\n";

        adapter.rewriteConfig("apalveien5", newContent);

        assertThat(Files.readString(configDir.resolve("apalveien5").resolve("apalveien5.conf")))
                .isEqualTo(newContent);
    }

    @Test
    void rewriteConfig_throwsWhenPeerDoesNotExist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.rewriteConfig("ghost", "x"))
            .isInstanceOf(net.vaier.domain.PeerNotFoundException.class)
            .hasMessageContaining("ghost");
    }

    // --- SSH access override (#307) ---

    @Test
    void updateSshAccess_writesOverrideIntoMetadata_andReadsBack() throws IOException {
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}");

        adapter.updateSshAccess("apalveien5", false);

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.sshAccess()).isFalse();
        assertThat(result.effectiveSshAccess()).isFalse();
        // Other metadata carries over untouched.
        assertThat(result.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
    }

    @Test
    void updateSshAccess_true_readsBackTrue() throws IOException {
        createPeerConfWithVaierMetadata("phone", "10.13.13.10",
                "{\"peerType\":\"MOBILE_CLIENT\"}");

        adapter.updateSshAccess("phone", true);

        PeerConfiguration result = adapter.getPeerConfigByName("phone").orElseThrow();
        assertThat(result.sshAccess()).isTrue();
        assertThat(result.effectiveSshAccess()).isTrue();
    }

    @Test
    void getPeerConfig_legacyWithoutSshAccess_readsNullOverride_andUsesDefault() throws IOException {
        // A pre-#307 config has no sshAccess key: override is null, effective = smart default (server → on).
        createPeerConfWithVaierMetadata("apalveien5", "10.13.13.6",
                "{\"peerType\":\"UBUNTU_SERVER\"}");

        PeerConfiguration result = adapter.getPeerConfigByName("apalveien5").orElseThrow();
        assertThat(result.sshAccess()).isNull();
        assertThat(result.effectiveSshAccess()).isTrue();
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
