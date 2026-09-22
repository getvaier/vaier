package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DbIpGeolocationAdapterTest {

    private static DbIpGeolocationAdapter adapterWithoutDb() {
        // Point at a non-existent path so the adapter starts up with reader == null.
        // All lookups should return Optional.empty() without throwing.
        DbIpGeolocationAdapter adapter = new DbIpGeolocationAdapter();
        adapter.setDbPath(Path.of("/nonexistent/path/to/dbip.mmdb").toString());
        adapter.init();
        return adapter;
    }

    @Test
    void locate_returnsEmptyForAddressesThatCannotBePlaced() {
        DbIpGeolocationAdapter adapter = adapterWithoutDb();
        String[] inputs = {
            null, "", "   ",
            "not-an-ip",
            "127.0.0.1", "::1",
            // RFC1918
            "10.0.0.5", "172.16.0.1", "172.20.0.10", "192.168.1.100",
            "169.254.1.1",
            // 100.64.0.0/10 — RFC 6598 CGNAT space; not flagged by InetAddress.isSiteLocalAddress()
            "100.64.5.1", "100.127.255.254",
            // fc00::/7 — IPv6 ULA, equivalent to RFC1918
            "fc00::1", "fd12:3456:789a::1",
            // Public IP with no DB loaded — should gracefully return empty, not throw.
            "8.8.8.8",
            // Garbled input must not throw either.
            "999.999.999.999", "1.2.3"
        };

        for (String input : inputs) {
            assertThat(adapter.locate(input)).as(String.valueOf(input)).isEmpty();
        }
    }

    @Test
    void init_doesNotThrowWhenDbFileMissing() {
        // Constructing the adapter with a missing DB must succeed — production deployment
        // may start before the geoip-init container has populated the volume.
        DbIpGeolocationAdapter adapter = new DbIpGeolocationAdapter();
        adapter.setDbPath("/nonexistent/dbip.mmdb");
        adapter.init();
        adapter.cleanup();
    }
}
