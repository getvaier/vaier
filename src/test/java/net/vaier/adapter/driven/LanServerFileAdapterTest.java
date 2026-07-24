package net.vaier.adapter.driven;

import net.vaier.domain.LanServer;
import net.vaier.domain.MachineId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LanServerFileAdapterTest {

    @TempDir
    Path tempDir;

    private LanServerFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LanServerFileAdapter(tempDir.toString());
    }

    @Test
    void getAll_emptyWhenFileDoesNotExist() {
        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void save_thenGetAll_roundTripsTheMachineId() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);

        adapter.save(nas);

        assertThat(adapter.getAll()).singleElement()
            .extracting(LanServer::machineId).isEqualTo(nas.machineId());
    }

    /**
     * A stored machine's identity is read, never minted: an entry with no {@code id} is a hand-edit
     * that has not been finished, and silently inventing one would mint a <em>new</em> machine that
     * no credential, backup job or disk watch is keyed to. The entry is skipped loudly instead, and
     * the rest of the file still loads — one bad line never costs the whole fleet.
     */
    @Test
    void getAll_skipsAnEntryWithNoMachineIdButStillLoadsTheRest() throws Exception {
        Files.writeString(tempDir.resolve("lan-servers.yml"), """
            servers:
            - name: no-id
              lanAddress: 192.168.3.11
              runsDocker: false
            - name: has-id
              lanAddress: 192.168.3.12
              runsDocker: false
              id: 3f2504e0-4f89-41d3-9a0c-0305e82c3301
            """);

        assertThat(adapter.getAll()).extracting(LanServer::name).containsExactly("has-id");
    }

    @Test
    void getAll_skipsAnEntryWhoseMachineIdIsMalformed() throws Exception {
        Files.writeString(tempDir.resolve("lan-servers.yml"), """
            servers:
            - name: bad-id
              lanAddress: 192.168.3.11
              runsDocker: false
              id: not-a-uuid
            """);

        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void save_runsDockerTrue_thenGetAll_returnsSavedServer() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);

        adapter.save(nas);

        assertThat(adapter.getAll()).containsExactly(nas);
    }

    @Test
    void save_runsDockerFalse_persistsWithoutDockerPort() {
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);

        adapter.save(printer);

        assertThat(adapter.getAll()).containsExactly(printer);
    }

    @Test
    void save_persistsToLanServersYamlFile() throws Exception {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));

        Path file = tempDir.resolve("lan-servers.yml");
        assertThat(Files.exists(file)).isTrue();
        String contents = Files.readString(file);
        assertThat(contents)
            .contains("nas")
            .contains("192.168.3.50")
            .contains("2375")
            .contains("runsDocker");
    }

    @Test
    void save_runsDockerFalse_doesNotWriteDockerPortKey() throws Exception {
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        String contents = Files.readString(tempDir.resolve("lan-servers.yml"));
        assertThat(contents).doesNotContain("dockerPort");
    }

    @Test
    void save_multipleServers_persistsAll() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);

        adapter.save(nas);
        adapter.save(printer);

        assertThat(adapter.getAll()).containsExactlyInAnyOrder(nas, printer);
    }

    @Test
    void save_existingName_replacesEntry() {
        LanServer replacement = new LanServer("nas", "192.168.3.99", true, 2376);

        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(replacement);

        assertThat(adapter.getAll()).containsExactly(replacement);
    }

    @Test
    void deleteByName_existingServer_removesIt() {
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(printer);

        adapter.deleteByName("nas");

        assertThat(adapter.getAll()).containsExactly(printer);
    }

    @Test
    void deleteByName_unknownServer_isNoOp() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));

        adapter.deleteByName("does-not-exist");

        assertThat(adapter.getAll()).hasSize(1);
    }

    @Test
    void getAll_roundTripsThroughFreshAdapter() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375);
        LanServer printer = new LanServer("printer", "192.168.3.20", false, null);
        adapter.save(nas);
        adapter.save(printer);

        LanServerFileAdapter reread = new LanServerFileAdapter(tempDir.toString());

        assertThat(reread.getAll()).containsExactlyInAnyOrder(nas, printer);
    }

    @Test
    void migration_renamesLegacyFileAndDefaultsRunsDockerTrue() throws Exception {
        Files.writeString(tempDir.resolve("lan-docker-hosts.yml"), """
            hosts:
              - name: nas
                hostIp: 192.168.3.50
                port: 2375
              - name: media
                hostIp: 192.168.3.51
                port: 2376
            """);

        LanServerFileAdapter migrated = new LanServerFileAdapter(tempDir.toString());

        // Promoting a pre-LanServer legacy file is the one place identity is minted rather than read,
        // so the ids are unpredictable — compare the fields the migration is responsible for, and
        // assert only that every promoted machine came away with an identity.
        assertThat(migrated.getAll())
            .extracting(LanServer::name, LanServer::lanAddress, LanServer::runsDocker, LanServer::dockerPort)
            .containsExactlyInAnyOrder(
                tuple("nas", "192.168.3.50", true, 2375),
                tuple("media", "192.168.3.51", true, 2376));
        assertThat(migrated.getAll()).allSatisfy(s -> assertThat(s.machineId()).isNotNull());
        assertThat(Files.exists(tempDir.resolve("lan-servers.yml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("lan-docker-hosts.yml"))).isFalse();
    }

    @Test
    void migration_idempotent_doesNotOverwriteWhenNewFileExists() throws Exception {
        Files.writeString(tempDir.resolve("lan-servers.yml"), """
            servers:
              - id: 3f2504e0-4f89-41d3-9a0c-0305e82c3301
                name: existing
                lanAddress: 10.0.0.1
                runsDocker: false
            """);
        Files.writeString(tempDir.resolve("lan-docker-hosts.yml"), """
            hosts:
              - name: legacy
                hostIp: 192.168.3.50
                port: 2375
            """);

        LanServerFileAdapter migrated = new LanServerFileAdapter(tempDir.toString());

        assertThat(migrated.getAll()).containsExactly(
            new LanServer("existing", "10.0.0.1", false, null, null, null, null,
                MachineId.of("3f2504e0-4f89-41d3-9a0c-0305e82c3301"))
        );
        assertThat(Files.exists(tempDir.resolve("lan-docker-hosts.yml"))).isTrue();
    }

    @Test
    void migration_skippedWhenLegacyAbsent() {
        LanServerFileAdapter migrated = new LanServerFileAdapter(tempDir.toString());

        assertThat(migrated.getAll()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("lan-servers.yml"))).isFalse();
    }

    // --- description (#54) ---

    @Test
    void save_withDescription_thenGetAll_roundTripsTheDescription() {
        LanServer nas = new LanServer("nas", "192.168.3.50", true, 2375, "Synology in the closet");

        adapter.save(nas);

        assertThat(adapter.getAll()).containsExactly(nas);
    }

    @Test
    void getAll_lanServerWithoutDescription_readsBackNullDescription() {
        // A pre-#54 lan-servers.yml entry has no `description` key — it must still load.
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        assertThat(adapter.getAll().get(0).description()).isNull();
    }

    // --- device category override ---

    @Test
    void save_withDeviceCategory_thenGetAll_roundTripsTheOverride() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375, null,
            net.vaier.domain.DeviceCategory.NAS));

        LanServer loaded = adapter.getAll().get(0);
        assertThat(loaded.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.NAS);
    }

    @Test
    void getAll_lanServerWithoutDeviceCategory_readsBackNullOverride() {
        // A pre-feature lan-servers.yml entry has no `deviceCategory` key — must still load.
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        assertThat(adapter.getAll().get(0).deviceCategory()).isNull();
    }

    @Test
    void save_deviceCategoryRoundTripsThroughFreshAdapter() {
        adapter.save(new LanServer("cam", "192.168.3.70", false, null, "Front door",
            net.vaier.domain.DeviceCategory.CAMERA));

        LanServerFileAdapter fresh = new LanServerFileAdapter(tempDir.toString());
        LanServer loaded = fresh.getAll().get(0);
        assertThat(loaded.deviceCategory()).isEqualTo(net.vaier.domain.DeviceCategory.CAMERA);
        assertThat(loaded.description()).isEqualTo("Front door");
    }

    // --- SSH access override (#307) ---

    @Test
    void save_withSshAccessOverride_roundTripsThroughFreshAdapter() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375, null, null, false,
            MachineId.generate()));

        LanServerFileAdapter fresh = new LanServerFileAdapter(tempDir.toString());
        LanServer loaded = fresh.getAll().get(0);
        assertThat(loaded.sshAccessOverride()).isFalse();
        assertThat(loaded.effectiveSshAccess()).isFalse();
    }

    @Test
    void getAll_lanServerWithoutSshAccessOverride_readsNull_andUsesDefault() {
        // A NAS with no override defaults to SSH-on.
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375, null,
            net.vaier.domain.DeviceCategory.NAS));

        LanServer loaded = adapter.getAll().get(0);
        assertThat(loaded.sshAccessOverride()).isNull();
        assertThat(loaded.effectiveSshAccess()).isTrue();
    }

    @Test
    void save_withoutOverride_doesNotWriteSshAccessKey() throws Exception {
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        String contents = Files.readString(tempDir.resolve("lan-servers.yml"));
        assertThat(contents).doesNotContain("sshAccessOverride");
    }
}
