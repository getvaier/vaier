package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanServerTest {

    private static final MachineId ID = MachineId.of("3f2504e0-4f89-41d3-9a0c-0305e82c3301");

    private static LanServer nas() {
        return new LanServer("NAS", "192.168.3.50", true, 2375, "the box", DeviceCategory.NAS, true, ID);
    }

    /**
     * The invariant this whole refactor exists for: a rename edits a label and touches nothing else.
     * Identity must survive it, or every record keyed on the machine is orphaned the moment someone
     * fixes a typo in a name.
     */
    @Test
    void renamedTo_preservesTheMachineId() {
        assertThat(nas().renamedTo("Storage").machineId()).isEqualTo(ID);
    }

    @Test
    void withDescription_preservesTheMachineId() {
        assertThat(nas().withDescription("something else").machineId()).isEqualTo(ID);
    }

    @Test
    void withDeviceCategory_preservesTheMachineId() {
        assertThat(nas().withDeviceCategory(DeviceCategory.PRINTER).machineId()).isEqualTo(ID);
    }

    @Test
    void withSshAccessOverride_preservesTheMachineId() {
        assertThat(nas().withSshAccessOverride(false).machineId()).isEqualTo(ID);
    }

    @Test
    void constructor_rejectsAMissingMachineId() {
        assertThatThrownBy(() -> new LanServer("NAS", "192.168.3.50", false, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The short constructors describe a machine being <em>created</em>, so they mint a fresh identity.
     * A machine being <em>read</em> from storage must carry the id it was stored with — that is the
     * eight-argument constructor, and the file adapter uses it.
     */
    @Test
    void shortConstructor_mintsAFreshIdentityForANewMachine() {
        LanServer one = new LanServer("NAS", "192.168.3.50", false, null);
        LanServer two = new LanServer("NAS", "192.168.3.50", false, null);
        assertThat(one.machineId()).isNotNull();
        assertThat(one.machineId()).isNotEqualTo(two.machineId());
    }

    @Test
    void validate_acceptsWellFormedCombinations_passes() {
        record Row(String description, String name, String lanAddress, boolean runsDocker, Integer port) {}
        List<Row> rows = List.of(
            new Row("runsDocker true with a valid port", "nas", "192.168.3.50", true, 2375),
            new Row("runsDocker false without a port", "printer", "192.168.3.20", false, null),
            new Row("runsDocker false with a port anyway", "printer", "192.168.3.20", false, 9100)
        );

        for (Row row : rows) {
            assertThatCode(() -> LanServer.validate(row.name(), row.lanAddress(), row.runsDocker(), row.port()))
                .as(row.description())
                .doesNotThrowAnyException();
        }
    }

    @Test
    void validate_rejectsBadInputs_namingWhatWasWrong() {
        record Row(String description, String name, String lanAddress, boolean runsDocker, Integer port, String expectedMessageFragment) {}
        List<Row> rows = List.of(
            new Row("runsDocker true without a port", "nas", "192.168.3.50", true, null, "dockerPort"),
            new Row("runsDocker true with port below 1", "nas", "192.168.3.50", true, 0, "dockerPort"),
            new Row("runsDocker true with port above 65535", "nas", "192.168.3.50", true, 70000, "dockerPort"),
            new Row("blank name", "  ", "192.168.3.50", true, 2375, "name"),
            new Row("null name", null, "192.168.3.50", true, 2375, "name"),
            // A name with CR/LF is never legitimate and would enable log forging if persisted.
            new Row("name with control characters", "nas\ninjected", "192.168.3.50", false, null, "name"),
            // The name is a /lan-servers/{name} path segment; a '/' makes the server unaddressable.
            new Row("name with slash", "nas/2", "192.168.3.50", false, null, "name"),
            new Row("blank lanAddress", "nas", "", true, 2375, "lanAddress"),
            new Row("non-IPv4 lanAddress", "nas", "not-an-ip", true, 2375, "lanAddress"),
            new Row("hostname as lanAddress, the rule is the shared lanAddress one", "NAS", "nas.home.example.com", false, null, "IPv4"),
            new Row("runsDocker false, lanAddress still validated", "printer", "not-an-ip", false, null, "lanAddress")
        );

        for (Row row : rows) {
            assertThatThrownBy(() -> LanServer.validate(row.name(), row.lanAddress(), row.runsDocker(), row.port()))
                .as(row.description())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(row.expectedMessageFragment());
        }
    }

    @Test
    void record_fieldsAreAccessible() {
        LanServer server = new LanServer("nas", "192.168.3.50", true, 2375);

        assertThat(server.name()).isEqualTo("nas");
        assertThat(server.lanAddress()).isEqualTo("192.168.3.50");
        assertThat(server.runsDocker()).isTrue();
        assertThat(server.dockerPort()).isEqualTo(2375);
    }

    @Test
    void record_runsDockerFalse_dockerPortMayBeNull() {
        LanServer server = new LanServer("printer", "192.168.3.20", false, null);

        assertThat(server.name()).isEqualTo("printer");
        assertThat(server.lanAddress()).isEqualTo("192.168.3.20");
        assertThat(server.runsDocker()).isFalse();
        assertThat(server.dockerPort()).isNull();
    }

    // --- hasName / renamedTo (#55) ---

    @Test
    void hasName_matchesOnlyExactName() {
        LanServer nas = new LanServer("nas", "192.168.1.50", false, null);

        assertThat(nas.hasName("nas")).isTrue();
        assertThat(nas.hasName("NAS")).isFalse();
        assertThat(nas.hasName("nas2")).isFalse();
    }

    @Test
    void renamedTo_copiesWithNewNameKeepingAddressAndDockerSettings() {
        LanServer renamed = new LanServer("nas", "192.168.1.50", true, 2375).renamedTo("media-nas");

        assertThat(renamed.name()).isEqualTo("media-nas");
        assertThat(renamed.lanAddress()).isEqualTo("192.168.1.50");
        assertThat(renamed.runsDocker()).isTrue();
        assertThat(renamed.dockerPort()).isEqualTo(2375);
    }

    @Test
    void renamedTo_trimsNewName() {
        assertThat(new LanServer("nas", "192.168.1.50", false, null).renamedTo("  media-nas  ").name())
            .isEqualTo("media-nas");
    }

    @Test
    void renamedTo_rejectsBadNewNames() {
        record Row(String description, String newName) {}
        List<Row> rows = List.of(
            new Row("blank name", "   "),
            new Row("name with control characters", "media\r\nnas"),
            new Row("name with slash", "media/nas")
        );

        LanServer nas = new LanServer("nas", "192.168.1.50", false, null);
        for (Row row : rows) {
            assertThatThrownBy(() -> nas.renamedTo(row.newName()))
                .as(row.description())
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- description (#54) ---

    @Test
    void fourArgConstructor_defaultsDescriptionToNull() {
        assertThat(new LanServer("nas", "192.168.1.50", false, null).description()).isNull();
    }

    @Test
    void withDescription_setsTheDescriptionKeepingEverythingElse() {
        LanServer s = new LanServer("nas", "192.168.1.50", true, 2375).withDescription("Synology in the closet");

        assertThat(s.description()).isEqualTo("Synology in the closet");
        assertThat(s.name()).isEqualTo("nas");
        assertThat(s.lanAddress()).isEqualTo("192.168.1.50");
        assertThat(s.runsDocker()).isTrue();
        assertThat(s.dockerPort()).isEqualTo(2375);
    }

    @Test
    void withDescription_trimsTheValue() {
        assertThat(new LanServer("nas", "192.168.1.50", false, null).withDescription("  spaced  ").description())
            .isEqualTo("spaced");
    }

    @Test
    void withDescription_blankOrNullClearsTheDescription() {
        LanServer base = new LanServer("nas", "192.168.1.50", false, null, "old text");

        assertThat(base.withDescription("   ").description()).isNull();
        assertThat(base.withDescription(null).description()).isNull();
    }

    @Test
    void renamedTo_carriesDescriptionOver() {
        LanServer renamed = new LanServer("nas", "192.168.1.50", false, null, "Synology")
            .renamedTo("storage-box");

        assertThat(renamed.name()).isEqualTo("storage-box");
        assertThat(renamed.description()).isEqualTo("Synology");
    }

    // --- findById (#221, by identity since §6.22) ---

    @Test
    void findById_returnsTheMatchingServer() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);

        Optional<LanServer> hit = LanServer.findById(printer.machineId(), List.of(nas, printer));

        assertThat(hit).contains(printer);
    }

    @Test
    void findById_unknownIdentity_returnsEmpty() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);

        assertThat(LanServer.findById(MachineId.generate(), List.of(nas))).isEmpty();
    }

    @Test
    void findById_tellsApartTwoServersSharingAName() {
        // The reason this lookup is by identity. Under findByName the first match won, so an operation
        // meant for the second machine would silently land on the first — and the two are different
        // hosts at different addresses, so it would land somewhere real and wrong.
        LanServer here = new LanServer("nas", "192.168.3.50", true, 2375);
        LanServer there = new LanServer("nas", "192.168.1.50", true, 2375);

        assertThat(LanServer.findById(there.machineId(), List.of(here, there))).contains(there);
    }

    @Test
    void findById_emptyList_returnsEmpty() {
        assertThat(LanServer.findById(MachineId.generate(), List.of())).isEmpty();
    }

    // --- device category (override + effective) ---

    @Test
    void existingConstructors_defaultDeviceCategoryOverrideToNull() {
        assertThat(new LanServer("nas", "192.168.1.50", false, null).deviceCategory()).isNull();
        assertThat(new LanServer("nas", "192.168.1.50", false, null, "desc").deviceCategory()).isNull();
    }

    @Test
    void effectiveDeviceCategory_detectsFromNameWhenNoOverride() {
        // No persisted LAN role; name keyword "synology" -> NAS.
        LanServer s = new LanServer("my-synology", "192.168.1.50", false, null, null, null);

        assertThat(s.effectiveDeviceCategory()).isEqualTo(DeviceCategory.NAS);
        assertThat(s.deviceCategoryOverridden()).isFalse();
    }

    @Test
    void effectiveDeviceCategory_fallsBackToGenericWhenNoSignal() {
        LanServer s = new LanServer("box-17", "192.168.1.50", false, null, null, null);

        assertThat(s.effectiveDeviceCategory()).isEqualTo(DeviceCategory.GENERIC);
        assertThat(s.deviceCategoryOverridden()).isFalse();
    }

    @Test
    void effectiveDeviceCategory_overrideWins() {
        LanServer s = new LanServer("my-synology", "192.168.1.50", false, null, null, DeviceCategory.PRINTER);

        assertThat(s.effectiveDeviceCategory()).isEqualTo(DeviceCategory.PRINTER);
        assertThat(s.deviceCategoryOverridden()).isTrue();
    }

    @Test
    void withDeviceCategory_setsOverrideKeepingEverythingElse() {
        LanServer s = new LanServer("nas", "192.168.1.50", true, 2375, "desc")
            .withDeviceCategory(DeviceCategory.NAS);

        assertThat(s.deviceCategory()).isEqualTo(DeviceCategory.NAS);
        assertThat(s.name()).isEqualTo("nas");
        assertThat(s.lanAddress()).isEqualTo("192.168.1.50");
        assertThat(s.runsDocker()).isTrue();
        assertThat(s.dockerPort()).isEqualTo(2375);
        assertThat(s.description()).isEqualTo("desc");
    }

    @Test
    void withDeviceCategory_nullClearsOverride() {
        LanServer s = new LanServer("nas", "192.168.1.50", false, null, null, DeviceCategory.PRINTER)
            .withDeviceCategory(null);

        assertThat(s.deviceCategory()).isNull();
        assertThat(s.deviceCategoryOverridden()).isFalse();
    }

    @Test
    void renamedTo_carriesDeviceCategoryOverride() {
        LanServer renamed = new LanServer("nas", "192.168.1.50", false, null, null, DeviceCategory.NAS)
            .renamedTo("storage");

        assertThat(renamed.deviceCategory()).isEqualTo(DeviceCategory.NAS);
    }

    @Test
    void withDescription_carriesDeviceCategoryOverride() {
        LanServer s = new LanServer("nas", "192.168.1.50", false, null, null, DeviceCategory.NAS)
            .withDescription("a description");

        assertThat(s.deviceCategory()).isEqualTo(DeviceCategory.NAS);
    }
}
