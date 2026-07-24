package net.vaier.adapter.driven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HostKeyFileAdapterTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @TempDir
    Path tempDir;

    private HostKeyFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HostKeyFileAdapter(tempDir.toString());
    }

    @Test
    void getFingerprint_emptyWhenNonePinned() {
        assertThat(adapter.getFingerprint(mid("nas"))).isEmpty();
    }

    @Test
    void pin_thenGet_roundTrips() {
        adapter.pin(mid("nas"), "SHA256:abc123");

        assertThat(adapter.getFingerprint(mid("nas"))).contains("SHA256:abc123");
    }

    @Test
    void pin_sameMachine_replacesFingerprint() {
        adapter.pin(mid("nas"), "SHA256:old");
        adapter.pin(mid("nas"), "SHA256:new");

        assertThat(adapter.getFingerprint(mid("nas"))).contains("SHA256:new");
    }

    @Test
    void clear_removesPin() {
        adapter.pin(mid("nas"), "SHA256:abc");
        adapter.pin(mid("router"), "SHA256:def");

        adapter.clear(mid("nas"));

        assertThat(adapter.getFingerprint(mid("nas"))).isEmpty();
        assertThat(adapter.getFingerprint(mid("router"))).contains("SHA256:def");
    }

    @Test
    void roundTripsThroughFreshAdapter() {
        adapter.pin(mid("Vaier server"), "SHA256:xyz");

        assertThat(new HostKeyFileAdapter(tempDir.toString()).getFingerprint(mid("Vaier server")))
            .contains("SHA256:xyz");
    }

    @Test
    void persistsToSshKnownHostsYaml() throws Exception {
        adapter.pin(mid("nas"), "SHA256:abc123");

        Path file = tempDir.resolve("ssh-known-hosts.yml");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file)).contains(mid("nas").value()).contains("SHA256:abc123");
    }
}
