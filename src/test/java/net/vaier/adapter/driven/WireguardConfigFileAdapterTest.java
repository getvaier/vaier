package net.vaier.adapter.driven;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.MachineType;
import net.vaier.domain.PeerNotFoundException;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireguardConfigFileAdapterTest {

    @TempDir Path configDir;

    WireguardConfigFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WireguardConfigFileAdapter();
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", configDir.toString());
    }

    // --- machine identity (#312 follow-up) ---

    private static final String ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Test
    void getPeerConfigByName_readsTheMachineIdFromVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2",
            "{\"id\":\"" + ID + "\",\"peerType\":\"UBUNTU_SERVER\"}");

        assertThat(adapter.getPeerConfigByName("laptop")).get()
            .extracting(p -> p.machineId().value()).isEqualTo(ID);
    }

    /**
     * A peer's identity is read, never minted. A conf with no {@code id} is an unfinished hand-edit,
     * and inventing one would produce a peer that is a stranger to its own credential and backup job.
     */
    @Test
    void getPeerConfigByName_isEmptyWhenTheConfCarriesNoMachineId() throws IOException {
        // Written raw: the shared fixture splices an id in, which is exactly what must be absent here.
        Path peerDir = configDir.resolve("laptop");
        Files.createDirectories(peerDir);
        Files.writeString(peerDir.resolve("laptop.conf"),
            "# VAIER: {\"peerType\":\"UBUNTU_SERVER\"}\n"
            + "[Interface]\nAddress=10.13.13.2/32\nPrivateKey=testkey\n");

        assertThat(adapter.getPeerConfigByName("laptop")).isEmpty();
    }

    @Test
    void getPeerConfigByName_isEmptyWhenTheMachineIdIsMalformed() throws IOException {
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2",
            "{\"id\":\"not-a-uuid\",\"peerType\":\"UBUNTU_SERVER\"}");

        assertThat(adapter.getPeerConfigByName("laptop")).isEmpty();
    }

    /**
     * The rename footgun, pinned: every {@code update*} rewrites the whole {@code # VAIER:} line, so a
     * field dropped from that rewrite is a field erased from disk. Losing the id here would orphan the
     * peer from everything keyed to it — the exact failure this identity work exists to end.
     */
    @Test
    void updateName_preservesTheMachineId() throws IOException {
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2",
            "{\"id\":\"" + ID + "\",\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateName("laptop", "Geir's laptop");

        assertThat(adapter.getPeerConfigByName("laptop")).get()
            .extracting(p -> p.machineId().value()).isEqualTo(ID);
    }

    @Test
    void updateDescription_preservesTheMachineId() throws IOException {
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2",
            "{\"id\":\"" + ID + "\",\"peerType\":\"UBUNTU_SERVER\"}");

        adapter.updateDescription("laptop", "the one in the bag");

        assertThat(adapter.getPeerConfigByName("laptop")).get()
            .extracting(p -> p.machineId().value()).isEqualTo(ID);
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
                "# VAIER: {\"id\":\"" + ID + "\"}\n"
                + "[Interface]\nAddress = 10.13.13.3/32\nPrivateKey = abc123\n");

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

    // --- resolvePeerIdByIp ---

    @Test
    void resolvePeerIdByIp_returnsPeerNameWhenFound() throws IOException {
        createPeerConf("my-server", "10.13.13.4");

        assertThat(adapter.resolvePeerIdByIp("10.13.13.4")).isEqualTo("my-server");
    }

    @Test
    void resolvePeerIdByIp_returnsIpWhenNoPeerFound() throws IOException {
        createPeerConf("laptop", "10.13.13.2");

        assertThat(adapter.resolvePeerIdByIp("10.13.13.99")).isEqualTo("10.13.13.99");
    }

    @Test
    void resolvePeerIdByIp_returnsIpWhenConfigDirMissing() {
        ReflectionTestUtils.setField(adapter, "wireguardConfigPath", "/nonexistent/path");

        assertThat(adapter.resolvePeerIdByIp("10.13.13.2")).isEqualTo("10.13.13.2");
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

    // --- updateLanAddress / updateLanCidr / updateDescription / updateDeviceCategory: shared shapes ---

    @Test
    void updateX_writesFieldIntoVaierMetadata() throws IOException {
        record Row(String label, String peer, BiConsumer<String, String> action, String value,
                   Function<PeerConfiguration, Object> getter, Object expected) {}
        List<Row> rows = List.of(
                new Row("lanAddress", "apalveien5", adapter::updateLanAddress, "192.168.3.121",
                        PeerConfiguration::lanAddress, "192.168.3.121"),
                new Row("lanCidr", "apalveien5", adapter::updateLanCidr, "192.168.3.0/24",
                        PeerConfiguration::lanCidr, "192.168.3.0/24"),
                new Row("description", "nuc", adapter::updateDescription, "Raspberry Pi in garage",
                        PeerConfiguration::description, "Raspberry Pi in garage"),
                new Row("deviceCategory", "nuc", adapter::updateDeviceCategory, "NAS",
                        PeerConfiguration::deviceCategory, DeviceCategory.NAS));

        for (Row row : rows) {
            createPeerConfWithVaierMetadata(row.peer(), "10.13.13.6", "{\"peerType\":\"UBUNTU_SERVER\"}");

            row.action().accept(row.peer(), row.value());

            PeerConfiguration result = adapter.getPeerConfigByName(row.peer()).orElseThrow();
            assertThat(row.getter().apply(result)).as(row.label()).isEqualTo(row.expected());
            assertThat(result.peerType()).as(row.label()).isEqualTo(MachineType.UBUNTU_SERVER);
        }
    }

    @Test
    void updateX_preservesTheSiblingField() throws IOException {
        record Row(String label, String peer, String seedJson, BiConsumer<String, String> action, String value,
                   Function<PeerConfiguration, Object> siblingGetter, Object siblingExpected,
                   Function<PeerConfiguration, Object> primaryGetter, Object primaryExpected) {}
        List<Row> rows = List.of(
                new Row("updateLanAddress preserves lanCidr", "apalveien5",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\"}",
                        adapter::updateLanAddress, "192.168.3.121",
                        PeerConfiguration::lanCidr, "192.168.3.0/24",
                        PeerConfiguration::lanAddress, "192.168.3.121"),
                new Row("updateLanCidr preserves lanAddress", "apalveien5",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"lanAddress\":\"192.168.3.121\"}",
                        adapter::updateLanCidr, "192.168.3.0/24",
                        PeerConfiguration::lanAddress, "192.168.3.121",
                        PeerConfiguration::lanCidr, "192.168.3.0/24"),
                new Row("updateLanAddress preserves description", "nuc",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"keep me\"}",
                        adapter::updateLanAddress, "192.168.3.121",
                        PeerConfiguration::description, "keep me", null, null),
                new Row("updateLanCidr preserves description", "nuc",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"keep me\"}",
                        adapter::updateLanCidr, "192.168.3.0/24",
                        PeerConfiguration::description, "keep me", null, null),
                new Row("updateDescription preserves deviceCategory", "nuc",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}",
                        adapter::updateDescription, "keep category",
                        PeerConfiguration::deviceCategory, DeviceCategory.NAS, null, null));

        for (Row row : rows) {
            createPeerConfWithVaierMetadata(row.peer(), "10.13.13.6", row.seedJson());

            row.action().accept(row.peer(), row.value());

            PeerConfiguration result = adapter.getPeerConfigByName(row.peer()).orElseThrow();
            assertThat(row.siblingGetter().apply(result)).as(row.label()).isEqualTo(row.siblingExpected());
            if (row.primaryGetter() != null) {
                assertThat(row.primaryGetter().apply(result)).as(row.label()).isEqualTo(row.primaryExpected());
            }
        }
    }

    @Test
    void updateX_blankClearsExistingValue() throws IOException {
        record Row(String label, String peer, String seedJson, BiConsumer<String, String> action,
                   Function<PeerConfiguration, Object> getter) {}
        List<Row> rows = List.of(
                new Row("lanAddress", "apalveien5",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"lanAddress\":\"192.168.3.121\"}",
                        adapter::updateLanAddress, PeerConfiguration::lanAddress),
                new Row("lanCidr", "apalveien5",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"lanCidr\":\"192.168.3.0/24\"}",
                        adapter::updateLanCidr, PeerConfiguration::lanCidr),
                new Row("description", "nuc",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"description\":\"old text\"}",
                        adapter::updateDescription, PeerConfiguration::description),
                new Row("deviceCategory", "nuc",
                        "{\"peerType\":\"UBUNTU_SERVER\",\"deviceCategory\":\"NAS\"}",
                        adapter::updateDeviceCategory, PeerConfiguration::deviceCategory));

        for (Row row : rows) {
            createPeerConfWithVaierMetadata(row.peer(), "10.13.13.6", row.seedJson());

            row.action().accept(row.peer(), "");

            PeerConfiguration result = adapter.getPeerConfigByName(row.peer()).orElseThrow();
            assertThat(row.getter().apply(result)).as(row.label()).isNull();
        }
    }

    @Test
    void updateX_addsVaierCommentWhenMissing() throws IOException {
        record Row(String label, String peer, BiConsumer<String, String> action, String value,
                   Function<PeerConfiguration, Object> getter, Object expected, boolean checksPeerType) {}
        List<Row> rows = List.of(
                new Row("lanAddress", "apalveien5", adapter::updateLanAddress, "192.168.3.121",
                        PeerConfiguration::lanAddress, "192.168.3.121", true),
                new Row("lanCidr", "apalveien5", adapter::updateLanCidr, "192.168.3.0/24",
                        PeerConfiguration::lanCidr, "192.168.3.0/24", true),
                new Row("description", "nuc", adapter::updateDescription, "Home media server",
                        PeerConfiguration::description, "Home media server", true),
                new Row("deviceCategory", "nuc", adapter::updateDeviceCategory, "PRINTER",
                        PeerConfiguration::deviceCategory, DeviceCategory.PRINTER, false));

        for (Row row : rows) {
            createPeerConf(row.peer(), "10.13.13.7");

            row.action().accept(row.peer(), row.value());

            PeerConfiguration result = adapter.getPeerConfigByName(row.peer()).orElseThrow();
            assertThat(row.getter().apply(result)).as(row.label()).isEqualTo(row.expected());
            if (row.checksPeerType()) {
                assertThat(result.peerType()).as(row.label()).isEqualTo(MachineType.UBUNTU_SERVER);
            }
        }
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
    void updateX_throwsWhenPeerDoesNotExist() {
        assertThat(adapter.getPeerConfigByName("ghost")).isEmpty();
        record Row(String label, ThrowingCallable action) {}
        List<Row> rows = List.of(
                new Row("lanAddress", () -> adapter.updateLanAddress("ghost", "192.168.3.121")),
                new Row("lanCidr", () -> adapter.updateLanCidr("ghost", "192.168.3.0/24")),
                new Row("description", () -> adapter.updateDescription("ghost", "anything")),
                new Row("name", () -> adapter.updateName("ghost", "Phantom")),
                new Row("deviceCategory", () -> adapter.updateDeviceCategory("ghost", "NAS")));

        for (Row row : rows) {
            assertThatThrownBy(row.action()).as(row.label()).isInstanceOf(PeerNotFoundException.class);
        }
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
    void updateDescription_roundTripsSpecialCharacters() throws IOException {
        createPeerConf("nuc", "10.13.13.7");

        adapter.updateDescription("nuc", "NAS \"box\" — line1\nline2");

        PeerConfiguration result = adapter.getPeerConfigByName("nuc").orElseThrow();
        assertThat(result.description()).isEqualTo("NAS \"box\" — line1\nline2");
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
    void getAllPeerConfigs_passesTheImagesOwnDirectoriesInSilence() throws IOException {
        // The WireGuard image keeps coredns/, server/ and templates/ beside the peers. None holds a
        // <name>/<name>.conf, which is the one thing that makes a directory a peer — so they are not
        // peers, and not worth a warning on every request either: 1500 an hour buried the real ones.
        createPeerConf("laptop", "10.13.13.2");
        for (String own : List.of("coredns", "server", "templates")) {
            Files.createDirectories(configDir.resolve(own));
        }
        Files.writeString(configDir.resolve("server").resolve("privatekey-server"), "k");

        List<ILoggingEvent> warnings = whileCapturingWarnings(() ->
            assertThat(adapter.getAllPeerConfigs()).extracting(PeerConfiguration::name)
                .containsExactly("laptop"));

        assertThat(warnings).isEmpty();
    }

    private static List<ILoggingEvent> whileCapturingWarnings(Runnable work) {
        Logger adapterLog = (Logger) LoggerFactory.getLogger(WireguardConfigFileAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adapterLog.addAppender(appender);
        try {
            work.run();
            return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        } finally {
            adapterLog.detachAppender(appender);
        }
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

    /**
     * A well-formed peer conf. Every peer Vaier can read carries a machine {@code id}, so the fixture
     * writes one — a test about anything else should not have to restate that.
     */
    private void createPeerConf(String peerName, String ip) throws IOException {
        createPeerConfWithVaierMetadata(peerName, ip, "{}");
    }

    /**
     * A peer conf carrying {@code vaierJson} as its {@code # VAIER:} line. A machine {@code id} is
     * spliced in when the supplied JSON does not already name one, so the many tests that pin some
     * other metadata field still describe a readable peer. Pass an explicit {@code "id"} to control it.
     */
    private void createPeerConfWithVaierMetadata(String peerName, String ip, String vaierJson)
            throws IOException {
        String json = vaierJson.contains("\"id\"")
            ? vaierJson
            : vaierJson.replaceFirst("^\\{", "{\"id\":\"" + java.util.UUID.randomUUID() + "\"" +
                (vaierJson.trim().equals("{}") ? "" : ","));
        Path peerDir = configDir.resolve(peerName);
        Files.createDirectories(peerDir);
        Files.writeString(peerDir.resolve(peerName + ".conf"),
                "# VAIER: " + json + "\n[Interface]\nAddress=" + ip + "/32\nPrivateKey=testkey\n");
    }

    // --- a device-held key survives every metadata rewrite (#359) ---

    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";

    @Test
    void getPeerConfigByName_readsTheDeviceHeldPublicKeyFromVaierMetadata() throws IOException {
        createPeerConfWithVaierMetadata("phone", "10.13.13.7",
            "{\"peerType\":\"MOBILE_CLIENT\",\"publicKey\":\"" + DEVICE_KEY + "\"}");

        assertThat(adapter.getPeerConfigByName("phone")).get()
            .extracting(PeerConfiguration::publicKey).isEqualTo(DEVICE_KEY);
    }

    @Test
    void getPeerConfigByName_publicKeyIsNullForAPeerWhoseKeyVaierMinted() throws IOException {
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2", "{\"peerType\":\"UBUNTU_SERVER\"}");

        assertThat(adapter.getPeerConfigByName("laptop")).get()
            .extracting(PeerConfiguration::publicKey).isNull();
    }

    /**
     * Every {@code update*} rewrites the whole {@code # VAIER:} line, so a field a mutator does not carry
     * is a field erased from disk. For a {@code Device-held key} that erasure is unrecoverable — Vaier has
     * no private key to derive the public one from — and it would silently re-open the peer's downloads.
     * One test per mutator, because the bug is per-mutator.
     */
    @Test
    void everyMetadataRewrite_preservesTheDeviceHeldPublicKey() throws IOException {
        record Mutation(String label, Runnable action) {}
        List<Mutation> mutations = List.of(
            new Mutation("rename", () -> adapter.updateName("phone", "Geir's phone")),
            new Mutation("description", () -> adapter.updateDescription("phone", "the daily driver")),
            new Mutation("deviceCategory", () -> adapter.updateDeviceCategory("phone", "PHONE")),
            new Mutation("lanAddress", () -> adapter.updateLanAddress("phone", "192.168.1.20")),
            new Mutation("lanCidr", () -> adapter.updateLanCidr("phone", "192.168.1.0/24")),
            new Mutation("sshAccess", () -> adapter.updateSshAccess("phone", true)));

        for (Mutation mutation : mutations) {
            Files.deleteIfExists(configDir.resolve("phone").resolve("phone.conf"));
            createPeerConfWithVaierMetadata("phone", "10.13.13.7",
                "{\"peerType\":\"MOBILE_CLIENT\",\"publicKey\":\"" + DEVICE_KEY + "\"}");

            mutation.action().run();

            assertThat(adapter.getPeerConfigByName("phone")).get()
                .extracting(PeerConfiguration::publicKey)
                .as("%s must not erase the device-held key", mutation.label())
                .isEqualTo(DEVICE_KEY);
        }
    }

    @Test
    void metadataParsing_toleratesAnUnknownKey() throws IOException {
        // Both directions of the compatibility question: a conf written by a newer Vaier must still load
        // on an older one, and a hand-added key must not take the peer down.
        createPeerConfWithVaierMetadata("laptop", "10.13.13.2",
            "{\"peerType\":\"UBUNTU_SERVER\",\"somethingNobodyHasWrittenYet\":\"x\"}");

        assertThat(adapter.getPeerConfigByName("laptop")).isPresent();
    }
}
