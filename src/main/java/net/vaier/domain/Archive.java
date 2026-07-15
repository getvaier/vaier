package net.vaier.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * One borg snapshot in a {@link BackupRepository} — borg's own term for a point-in-time backup. It owns
 * the decision of how to read borg's {@code borg list --json} output ({@link #parseList(String)}): that
 * parsing is a domain rule, so it lives here rather than in the rest-layer runner that fetches the JSON.
 *
 * <p>Like {@link RemoteDiskUsage#parse}, {@link #parseList} never throws: blank, unparseable, or
 * unexpectedly-shaped input yields an empty list ("cannot tell"), never an exception the caller must
 * catch. borg emits archive times as a zone-less ISO local timestamp; a time that cannot be read is
 * carried as {@code null} rather than failing the whole parse.
 *
 * @param name the archive name (borg's {@code archive} field, e.g. {@code colina-2024-06-01T02:00:00})
 * @param id   the archive id borg assigns
 * @param time when the archive was created, or {@code null} when borg reported no readable time
 */
public record Archive(String name, String id, Instant time) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse the {@code archives} array of a {@code borg list --json} document into {@link Archive}s.
     * Returns an empty list on blank/unparseable input or when the {@code archives} field is missing or
     * not an array — never throws.
     */
    public static List<Archive> parseList(String borgListJson) {
        if (borgListJson == null || borgListJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode archives = MAPPER.readTree(borgListJson).get("archives");
            if (archives == null || !archives.isArray()) {
                return List.of();
            }
            List<Archive> result = new ArrayList<>();
            for (JsonNode node : archives) {
                result.add(new Archive(text(node, "archive"), text(node, "id"), parseTime(text(node, "time"))));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * {@code archives} ordered newest first — what the Explorer's time rail scrubs over. An archive whose
     * time borg reported as unreadable ({@code null}) sinks to the bottom rather than jumping the order or
     * throwing. Ordering the rail is a decision about archives, so it lives here on the entity.
     */
    public static List<Archive> newestFirst(List<Archive> archives) {
        return archives.stream()
            .sorted(java.util.Comparator.comparing(Archive::time,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Read a borg archive time. borg emits a zone-less ISO local timestamp
     * ({@code 2024-06-01T02:00:00.000000}), which we anchor to UTC; a plain {@link Instant} string is
     * also accepted. Anything unreadable yields {@code null} rather than failing the surrounding parse.
     */
    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            // Not an offset instant; fall through to the zone-less local form borg actually emits.
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }
}
