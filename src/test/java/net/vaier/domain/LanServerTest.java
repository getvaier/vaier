package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void validate_runsDockerTrueWithValidPort_passes() {
        LanServer.validate("nas", "192.168.3.50", true, 2375);
    }

    @Test
    void validate_runsDockerFalseWithoutPort_passes() {
        LanServer.validate("printer", "192.168.3.20", false, null);
    }

    @Test
    void validate_runsDockerFalseWithPort_passes() {
        LanServer.validate("printer", "192.168.3.20", false, 9100);
    }

    @Test
    void validate_runsDockerTrueWithoutPort_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_runsDockerTrueWithPortBelowOne_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_runsDockerTrueWithPortAbove65535_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "192.168.3.50", true, 70000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dockerPort");
    }

    @Test
    void validate_blankName_throws() {
        assertThatThrownBy(() -> LanServer.validate("  ", "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_nullName_throws() {
        assertThatThrownBy(() -> LanServer.validate(null, "192.168.3.50", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_nameWithControlCharacters_throws() {
        // A name with CR/LF is never legitimate and would enable log forging if persisted.
        assertThatThrownBy(() -> LanServer.validate("nas\ninjected", "192.168.3.50", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_nameWithSlash_throws() {
        // The name is a /lan-servers/{name} path segment; a '/' makes the server unaddressable.
        assertThatThrownBy(() -> LanServer.validate("nas/2", "192.168.3.50", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void validate_blankLanAddress_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
    }

    @Test
    void validate_nonIpv4LanAddress_throws() {
        assertThatThrownBy(() -> LanServer.validate("nas", "not-an-ip", true, 2375))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
    }

    @Test
    void validate_runsDockerFalse_lanAddressStillValidated() {
        assertThatThrownBy(() -> LanServer.validate("printer", "not-an-ip", false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanAddress");
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
    void renamedTo_rejectsBlankName() {
        LanServer nas = new LanServer("nas", "192.168.1.50", false, null);

        assertThatThrownBy(() -> nas.renamedTo("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamedTo_rejectsNameWithControlCharacters() {
        LanServer nas = new LanServer("nas", "192.168.1.50", false, null);

        assertThatThrownBy(() -> nas.renamedTo("media\r\nnas"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamedTo_rejectsNameWithSlash() {
        LanServer nas = new LanServer("nas", "192.168.1.50", false, null);

        assertThatThrownBy(() -> nas.renamedTo("media/nas"))
            .isInstanceOf(IllegalArgumentException.class);
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

    // --- findByName (#221) ---

    @Test
    void findByName_returnsTheMatchingServer() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);

        Optional<LanServer> hit = LanServer.findByName("printer", List.of(nas, printer));

        assertThat(hit).contains(printer);
    }

    @Test
    void findByName_unknownName_returnsEmpty() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);

        assertThat(LanServer.findByName("ghost", List.of(nas))).isEmpty();
    }

    @Test
    void findByName_isCaseSensitive() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);

        assertThat(LanServer.findByName("NAS", List.of(nas))).isEmpty();
    }

    @Test
    void findByName_emptyList_returnsEmpty() {
        assertThat(LanServer.findByName("nas", List.of())).isEmpty();
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
