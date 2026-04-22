package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class CloudflareDnsAdapter implements ForPersistingDnsRecords {

    private static final String API_BASE = "https://api.cloudflare.com/client/v4";

    private final ConfigResolver configResolver;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public CloudflareDnsAdapter(ConfigResolver configResolver) {
        this(configResolver, HttpClient.newHttpClient());
    }

    CloudflareDnsAdapter(ConfigResolver configResolver, HttpClient httpClient) {
        this.configResolver = configResolver;
        this.httpClient = httpClient;
    }

    @Override
    public List<DnsZone> getDnsZones() {
        String token = configResolver.getCloudflareToken();
        if (token == null || token.isBlank()) {
            return List.of();
        }

        List<DnsZone> zones = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode body = get("/zones?per_page=50&page=" + page, token);
            for (JsonNode zone : body.path("result")) {
                zones.add(new DnsZone(zone.path("name").asText()));
            }
            int totalPages = body.path("result_info").path("total_pages").asInt(1);
            if (page >= totalPages) {
                break;
            }
            page++;
        }
        return zones;
    }

    @Override
    public List<DnsRecord> getDnsRecords(DnsZone dnsZone) {
        requireToken();
        String zoneId = findZoneId(dnsZone.name());
        if (zoneId == null) {
            return List.of();
        }

        Map<String, DnsRecord> grouped = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            JsonNode body = get("/zones/" + zoneId + "/dns_records?per_page=100&page=" + page,
                    configResolver.getCloudflareToken());
            for (JsonNode rec : body.path("result")) {
                DnsRecordType type = DnsRecordType.valueOf(rec.path("type").asText());
                String name = rec.path("name").asText();
                long ttl = rec.path("ttl").asLong(300);
                String value = decodeValue(rec);
                String key = name + "|" + type.name();
                DnsRecord existing = grouped.get(key);
                if (existing == null) {
                    List<String> values = new ArrayList<>();
                    values.add(value);
                    grouped.put(key, new DnsRecord(name, type, ttl, values));
                } else {
                    List<String> combined = new ArrayList<>(existing.values());
                    combined.add(value);
                    grouped.put(key, new DnsRecord(existing.name(), existing.type(), existing.ttl(), combined));
                }
            }
            int totalPages = body.path("result_info").path("total_pages").asInt(1);
            if (page >= totalPages) {
                break;
            }
            page++;
        }
        return new ArrayList<>(grouped.values());
    }

    @Override
    public void addDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        requireToken();
        String zoneId = findZoneId(dnsZone.name());
        if (zoneId == null) {
            throw new RuntimeException("Cloudflare zone not found: " + dnsZone.name());
        }
        for (String value : dnsRecord.values()) {
            ObjectNode body = buildRecordBody(dnsRecord, value);
            post("/zones/" + zoneId + "/dns_records", body, configResolver.getCloudflareToken());
        }
    }

    @Override
    public void updateDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        deleteDnsRecord(dnsRecord.name(), dnsRecord.type(), dnsZone);
        addDnsRecord(dnsRecord, dnsZone);
    }

    @Override
    public void deleteDnsRecord(String recordName, DnsRecordType recordType, DnsZone dnsZone) {
        requireToken();
        String zoneId = findZoneId(dnsZone.name());
        if (zoneId == null) {
            throw new RuntimeException("Cloudflare zone not found: " + dnsZone.name());
        }

        List<String> idsToDelete = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode body = get("/zones/" + zoneId + "/dns_records?per_page=100&page=" + page,
                    configResolver.getCloudflareToken());
            for (JsonNode rec : body.path("result")) {
                if (recordName.equals(rec.path("name").asText())
                        && recordType.name().equals(rec.path("type").asText())) {
                    idsToDelete.add(rec.path("id").asText());
                }
            }
            int totalPages = body.path("result_info").path("total_pages").asInt(1);
            if (page >= totalPages) {
                break;
            }
            page++;
        }

        for (String id : idsToDelete) {
            delete("/zones/" + zoneId + "/dns_records/" + id, configResolver.getCloudflareToken());
        }
    }

    @Override
    public void addDnsZone(DnsZone dnsZone) {
        throw new UnsupportedOperationException(
                "Creating zones via Cloudflare API requires an account_id; create the zone in the Cloudflare dashboard and re-scan.");
    }

    @Override
    public void updateDnsZone(DnsZone dnsZone) {
        throw new UnsupportedOperationException("Updating zones is not supported by Cloudflare");
    }

    @Override
    public void deleteDnsZone(DnsZone dnsZone) {
        requireToken();
        String zoneId = findZoneId(dnsZone.name());
        if (zoneId == null) {
            throw new RuntimeException("Cloudflare zone not found: " + dnsZone.name());
        }
        delete("/zones/" + zoneId, configResolver.getCloudflareToken());
    }

    private String findZoneId(String zoneName) {
        String token = configResolver.getCloudflareToken();
        JsonNode body = get("/zones?name=" + URLEncoder.encode(zoneName, StandardCharsets.UTF_8), token);
        for (JsonNode zone : body.path("result")) {
            if (zoneName.equals(zone.path("name").asText())) {
                return zone.path("id").asText();
            }
        }
        return null;
    }

    private void requireToken() {
        String t = configResolver.getCloudflareToken();
        if (t == null || t.isBlank()) {
            throw new IllegalStateException(
                    "Cloudflare token is not configured. Set VAIER_CLOUDFLARE_TOKEN.");
        }
    }

    private JsonNode get(String path, String token) {
        return send(HttpRequest.newBuilder(URI.create(API_BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET()
                .build());
    }

    private JsonNode post(String path, ObjectNode body, String token) {
        try {
            return send(HttpRequest.newBuilder(URI.create(API_BASE + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize Cloudflare request", e);
        }
    }

    private JsonNode delete(String path, String token) {
        return send(HttpRequest.newBuilder(URI.create(API_BASE + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .DELETE()
                .build());
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException(
                        "Cloudflare API " + req.method() + " " + req.uri() + " returned " + resp.statusCode() + ": " + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Cloudflare API call interrupted", e);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Cloudflare API call failed: " + req.uri(), e);
        }
    }

    private ObjectNode buildRecordBody(DnsRecord record, String value) {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", record.type().getValue());
        body.put("name", record.name());
        body.put("ttl", record.ttl() == null ? 300 : record.ttl());
        if (record.type() == DnsRecordType.MX) {
            int sp = value.indexOf(' ');
            if (sp > 0) {
                body.put("priority", Integer.parseInt(value.substring(0, sp).trim()));
                body.put("content", value.substring(sp + 1).trim());
            } else {
                body.put("content", value);
            }
        } else {
            body.put("content", value);
        }
        return body;
    }

    private String decodeValue(JsonNode rec) {
        if ("MX".equals(rec.path("type").asText())) {
            return rec.path("priority").asInt(0) + " " + rec.path("content").asText();
        }
        return rec.path("content").asText();
    }
}
