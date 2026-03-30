package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NetdataAdapterTest {

    HttpServer server;
    int port;
    NetdataAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        port = server.getAddress().getPort();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        adapter = new NetdataAdapter(httpClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // --- fetchMetrics ---

    @Test
    void fetchMetrics_parsesValidChartResponse() {
        serveChart("/api/v1/data",
                """
                {"labels":["time","user","system"],"data":[[1700000000,42.5,10.2]]}
                """);

        Map<String, Map<String, Double>> result = adapter.fetchMetrics("127.0.0.1:" + port);

        // At least one chart should have been fetched and parsed
        assertThat(result).isNotEmpty();
        Map<String, Double> firstChart = result.values().iterator().next();
        assertThat(firstChart).containsKey("user");
    }

    @Test
    void fetchMetrics_skipsChartOnHttpError() {
        server.createContext("/api/v1/data", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        // Should not throw; silently skips failed charts
        Map<String, Map<String, Double>> result = adapter.fetchMetrics("127.0.0.1:" + port);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchMetrics_skipsChartOnMalformedJson() {
        serveChart("/api/v1/data", "not-valid-json");

        Map<String, Map<String, Double>> result = adapter.fetchMetrics("127.0.0.1:" + port);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchMetrics_skipsChartWhenDataArrayIsEmpty() {
        serveChart("/api/v1/data",
                """
                {"labels":["time","user"],"data":[]}
                """);

        Map<String, Map<String, Double>> result = adapter.fetchMetrics("127.0.0.1:" + port);
        assertThat(result).isEmpty();
    }

    @Test
    void fetchMetrics_returnsEmptyMapWhenHostUnreachable() {
        // Port 1 is not listening
        Map<String, Map<String, Double>> result = adapter.fetchMetrics("127.0.0.1:1");
        assertThat(result).isEmpty();
    }

    @Test
    void fetchMetrics_fetchesAllChartsIndependently() {
        AtomicInteger requestCount = new AtomicInteger(0);
        server.createContext("/api/v1/data", exchange -> {
            requestCount.incrementAndGet();
            String body = """
                    {"labels":["time","value"],"data":[[1700000000,1.0]]}
                    """;
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });

        adapter.fetchMetrics("127.0.0.1:" + port);

        // Should have attempted all 10 charts defined in NetdataAdapter
        assertThat(requestCount.get()).isEqualTo(10);
    }

    // helper

    private void serveChart(String path, String body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.getBytes().length);
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });
    }
}
