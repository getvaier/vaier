package net.vaier.adapter.driven;

import net.vaier.domain.GeoLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

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
    void locate_returnsEmptyForNullIp() {
        assertThat(adapterWithoutDb().locate(null)).isEmpty();
    }

    @Test
    void locate_returnsEmptyForBlankIp() {
        assertThat(adapterWithoutDb().locate("")).isEmpty();
        assertThat(adapterWithoutDb().locate("   ")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForUnparseableIp() {
        assertThat(adapterWithoutDb().locate("not-an-ip")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForLoopbackV4() {
        assertThat(adapterWithoutDb().locate("127.0.0.1")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForLoopbackV6() {
        assertThat(adapterWithoutDb().locate("::1")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForRfc1918() {
        DbIpGeolocationAdapter adapter = adapterWithoutDb();
        assertThat(adapter.locate("10.0.0.5")).isEmpty();
        assertThat(adapter.locate("172.16.0.1")).isEmpty();
        assertThat(adapter.locate("172.20.0.10")).isEmpty();
        assertThat(adapter.locate("192.168.1.100")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForLinkLocal() {
        assertThat(adapterWithoutDb().locate("169.254.1.1")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForCgnat() {
        DbIpGeolocationAdapter adapter = adapterWithoutDb();
        // 100.64.0.0/10 — RFC 6598 CGNAT space; not flagged by InetAddress.isSiteLocalAddress()
        assertThat(adapter.locate("100.64.5.1")).isEmpty();
        assertThat(adapter.locate("100.127.255.254")).isEmpty();
    }

    @Test
    void locate_returnsEmptyForIpv6UniqueLocal() {
        // fc00::/7 — IPv6 ULA, equivalent to RFC1918
        assertThat(adapterWithoutDb().locate("fc00::1")).isEmpty();
        assertThat(adapterWithoutDb().locate("fd12:3456:789a::1")).isEmpty();
    }

    @Test
    void locate_returnsEmptyWhenDbFileMissing() {
        // Public IP with no DB loaded — should gracefully return empty, not throw.
        assertThat(adapterWithoutDb().locate("8.8.8.8")).isEmpty();
    }

    @Test
    void locate_doesNotThrowOnGarbledInput() {
        DbIpGeolocationAdapter adapter = adapterWithoutDb();
        // Each call should return empty without throwing.
        Optional<GeoLocation> result = adapter.locate("999.999.999.999");
        assertThat(result).isEmpty();
        result = adapter.locate("1.2.3");
        assertThat(result).isEmpty();
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
