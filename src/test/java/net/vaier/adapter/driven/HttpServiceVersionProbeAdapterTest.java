package net.vaier.adapter.driven;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpServiceVersionProbeAdapterTest {

    /** The dev's real example response — a Prometheus text-exposition gauge with labelled values. */
    private static final String SYSTEM_INFO =
        "# HELP system_info System information\n" +
        "# TYPE system_info gauge\n" +
        "system_info{version=\"5.0.0.0\",build_number=\"a0fdfff02ba\"," +
        "display=\"v.5.0.0.0_DEV#a0fdfff02ba\",dev_build=\"true\",beta_build=\"true\"," +
        "release_type=\"DEV\",build_year=\"2026\",} 1.0\n";

    private final HttpServiceVersionProbeAdapter adapter = new HttpServiceVersionProbeAdapter();

    @Test
    void extractProperty_readsTheLabelledValueFromPrometheusExposition() {
        assertThat(HttpServiceVersionProbeAdapter.extractProperty(SYSTEM_INFO, "display"))
            .contains("v.5.0.0.0_DEV#a0fdfff02ba");
    }

    @Test
    void extractProperty_readsADifferentProperty() {
        assertThat(HttpServiceVersionProbeAdapter.extractProperty(SYSTEM_INFO, "version"))
            .contains("5.0.0.0");
    }

    @Test
    void extractProperty_emptyWhenPropertyAbsent() {
        assertThat(HttpServiceVersionProbeAdapter.extractProperty(SYSTEM_INFO, "nonexistent"))
            .isEmpty();
    }

    @Test
    void extractProperty_emptyForNullBody() {
        assertThat(HttpServiceVersionProbeAdapter.extractProperty(null, "display")).isEmpty();
    }

    @Test
    void probeVersion_fetchesAndExtractsOverHttp() throws Exception {
        HttpServer server = startServer(200, SYSTEM_INFO);
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/sys/metrics";
            assertThat(adapter.probeVersion(url, "display")).contains("v.5.0.0.0_DEV#a0fdfff02ba");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeVersion_emptyOnNon200() throws Exception {
        HttpServer server = startServer(404, "not found");
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/sys/metrics";
            assertThat(adapter.probeVersion(url, "display")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeVersion_emptyWhenUnreachable() {
        // Nothing listens on port 1 — connection refused, probe must fail soft.
        assertThat(adapter.probeVersion("http://localhost:1/sys/metrics", "display")).isEmpty();
    }

    @Test
    void probeVersion_emptyForBlankInputs() {
        assertThat(adapter.probeVersion(null, "display")).isEmpty();
        assertThat(adapter.probeVersion("http://example.test/y", "  ")).isEmpty();
    }

    private static HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}
