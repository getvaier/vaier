package net.vaier.adapter.driven;

import net.vaier.domain.LanDockerHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanDockerHostFileAdapterTest {

    @TempDir
    Path tempDir;

    private LanDockerHostFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LanDockerHostFileAdapter(tempDir.toString());
    }

    @Test
    void getAll_emptyWhenFileDoesNotExist() {
        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void save_thenGetAll_returnsSavedHost() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));

        assertThat(adapter.getAll()).containsExactly(new LanDockerHost("nas", "192.168.3.50", 2375));
    }

    @Test
    void save_persistsToYamlFile() throws Exception {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));

        String contents = Files.readString(tempDir.resolve("lan-docker-hosts.yml"));
        assertThat(contents).contains("nas").contains("192.168.3.50").contains("2375");
    }

    @Test
    void save_multipleHosts_persistsAll() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));
        adapter.save(new LanDockerHost("printer", "192.168.3.51", 2375));

        assertThat(adapter.getAll()).containsExactlyInAnyOrder(
            new LanDockerHost("nas", "192.168.3.50", 2375),
            new LanDockerHost("printer", "192.168.3.51", 2375)
        );
    }

    @Test
    void save_existingName_replacesEntry() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));
        adapter.save(new LanDockerHost("nas", "192.168.3.99", 2376));

        assertThat(adapter.getAll()).containsExactly(new LanDockerHost("nas", "192.168.3.99", 2376));
    }

    @Test
    void deleteByName_existingHost_removesIt() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));
        adapter.save(new LanDockerHost("printer", "192.168.3.51", 2375));

        adapter.deleteByName("nas");

        assertThat(adapter.getAll()).containsExactly(new LanDockerHost("printer", "192.168.3.51", 2375));
    }

    @Test
    void deleteByName_unknownHost_isNoOp() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));

        adapter.deleteByName("does-not-exist");

        assertThat(adapter.getAll()).hasSize(1);
    }

    @Test
    void getAll_roundTripsThroughFreshAdapter() {
        adapter.save(new LanDockerHost("nas", "192.168.3.50", 2375));

        LanDockerHostFileAdapter reread = new LanDockerHostFileAdapter(tempDir.toString());

        assertThat(reread.getAll()).containsExactly(new LanDockerHost("nas", "192.168.3.50", 2375));
    }
}
