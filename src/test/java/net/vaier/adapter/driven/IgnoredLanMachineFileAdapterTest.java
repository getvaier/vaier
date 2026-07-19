package net.vaier.adapter.driven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoredLanMachineFileAdapterTest {

    @TempDir
    Path tempDir;

    private IgnoredLanMachineFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IgnoredLanMachineFileAdapter(tempDir.toString());
    }

    @Test
    void getIgnoredKeys_emptyWhenFileDoesNotExist() {
        assertThat(adapter.getIgnoredKeys()).isEmpty();
    }

    @Test
    void ignore_persistsTheKey() {
        adapter.ignore("apalveien5|192.168.3.111");

        assertThat(adapter.getIgnoredKeys()).containsExactly("apalveien5|192.168.3.111");
    }

    @Test
    void ignore_survivesReloadThroughAFreshAdapter() {
        adapter.ignore("apalveien5|192.168.3.111");
        adapter.ignore("colina27|192.168.1.5");

        IgnoredLanMachineFileAdapter fresh = new IgnoredLanMachineFileAdapter(tempDir.toString());
        assertThat(fresh.getIgnoredKeys())
            .containsExactlyInAnyOrder("apalveien5|192.168.3.111", "colina27|192.168.1.5");
    }

    @Test
    void ignore_isIdempotent_doesNotDuplicate() {
        adapter.ignore("apalveien5|192.168.3.111");
        adapter.ignore("apalveien5|192.168.3.111");

        assertThat(adapter.getIgnoredKeys()).containsExactly("apalveien5|192.168.3.111");
    }

    @Test
    void unignore_removesTheKey() {
        adapter.ignore("apalveien5|192.168.3.111");
        adapter.ignore("colina27|192.168.1.5");

        adapter.unignore("apalveien5|192.168.3.111");

        assertThat(adapter.getIgnoredKeys()).containsExactly("colina27|192.168.1.5");
    }

    @Test
    void unignore_unknownKey_isNoOp() {
        adapter.ignore("apalveien5|192.168.3.111");

        adapter.unignore("does-not-exist|1.2.3.4");

        assertThat(adapter.getIgnoredKeys()).containsExactly("apalveien5|192.168.3.111");
    }

    @Test
    void ignore_persistsToTheIgnoredLanMachinesYamlFile() throws Exception {
        adapter.ignore("apalveien5|192.168.3.111");

        Path file = tempDir.resolve("ignored-lan-machines.yml");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file))
            .contains("keys")
            .contains("apalveien5|192.168.3.111");
    }
}
