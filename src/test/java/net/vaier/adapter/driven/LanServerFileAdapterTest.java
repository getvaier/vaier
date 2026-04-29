package net.vaier.adapter.driven;

import net.vaier.domain.LanServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
    void save_runsDockerTrue_thenGetAll_returnsSavedServer() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));

        assertThat(adapter.getAll()).containsExactly(new LanServer("nas", "192.168.3.50", true, 2375));
    }

    @Test
    void save_runsDockerFalse_persistsWithoutDockerPort() {
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        assertThat(adapter.getAll()).containsExactly(new LanServer("printer", "192.168.3.20", false, null));
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
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        assertThat(adapter.getAll()).containsExactlyInAnyOrder(
            new LanServer("nas", "192.168.3.50", true, 2375),
            new LanServer("printer", "192.168.3.20", false, null)
        );
    }

    @Test
    void save_existingName_replacesEntry() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(new LanServer("nas", "192.168.3.99", true, 2376));

        assertThat(adapter.getAll()).containsExactly(new LanServer("nas", "192.168.3.99", true, 2376));
    }

    @Test
    void deleteByName_existingServer_removesIt() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        adapter.deleteByName("nas");

        assertThat(adapter.getAll()).containsExactly(new LanServer("printer", "192.168.3.20", false, null));
    }

    @Test
    void deleteByName_unknownServer_isNoOp() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));

        adapter.deleteByName("does-not-exist");

        assertThat(adapter.getAll()).hasSize(1);
    }

    @Test
    void getAll_roundTripsThroughFreshAdapter() {
        adapter.save(new LanServer("nas", "192.168.3.50", true, 2375));
        adapter.save(new LanServer("printer", "192.168.3.20", false, null));

        LanServerFileAdapter reread = new LanServerFileAdapter(tempDir.toString());

        assertThat(reread.getAll()).containsExactlyInAnyOrder(
            new LanServer("nas", "192.168.3.50", true, 2375),
            new LanServer("printer", "192.168.3.20", false, null)
        );
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

        assertThat(migrated.getAll()).containsExactlyInAnyOrder(
            new LanServer("nas", "192.168.3.50", true, 2375),
            new LanServer("media", "192.168.3.51", true, 2376)
        );
        assertThat(Files.exists(tempDir.resolve("lan-servers.yml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("lan-docker-hosts.yml"))).isFalse();
    }

    @Test
    void migration_idempotent_doesNotOverwriteWhenNewFileExists() throws Exception {
        Files.writeString(tempDir.resolve("lan-servers.yml"), """
            servers:
              - name: existing
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
            new LanServer("existing", "10.0.0.1", false, null)
        );
        assertThat(Files.exists(tempDir.resolve("lan-docker-hosts.yml"))).isTrue();
    }

    @Test
    void migration_skippedWhenLegacyAbsent() {
        LanServerFileAdapter migrated = new LanServerFileAdapter(tempDir.toString());

        assertThat(migrated.getAll()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("lan-servers.yml"))).isFalse();
    }
}
