package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForFetchingPeerMetrics;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class NetdataAdapter implements ForFetchingPeerMetrics {

    private static final int NETDATA_PORT = 19999;
    private static final List<String> CHARTS = List.of(
            "system.cpu",
            "system.ram",
            "system.load",
            "system.uptime",
            "disk_space./",
            "disk_busy.nvme0n1",
            "net.ens5",
            "net.eth0",
            "net.wg0",
            "docker_local.containers_state"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public NetdataAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Map<String, Map<String, Double>> fetchMetrics(String peerIp) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (String chart : CHARTS) {
            try {
                String url = "http://" + peerIp + ":" + NETDATA_PORT
                        + "/api/v1/data?chart=" + chart + "&points=1&format=json&after=-60";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode node = objectMapper.readTree(response.body());
                    JsonNode data = node.path("data");
                    JsonNode labels = node.path("labels");
                    if (data.isArray() && !data.isEmpty() && labels.isArray()) {
                        Map<String, Double> chartData = new LinkedHashMap<>();
                        JsonNode row = data.get(0);
                        for (int i = 1; i < labels.size(); i++) {
                            chartData.put(labels.get(i).asText(), row.get(i).asDouble());
                        }
                        result.put(chart, chartData);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to fetch Netdata chart '{}' from {}: {}", chart, peerIp, e.getMessage());
            }
        }
        return result;
    }
}
