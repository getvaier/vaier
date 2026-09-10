package net.vaier.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The exact length of a zip written uncompressed, streamed, with a data descriptor after each entry (#360).
 * Every part of such a zip has a fixed size but the names and the data, so the whole is known before the
 * first byte streams — and a download that knows its length can tell the browser, which then shows how far
 * along it is. Photos and videos do not compress anyway, so nothing is given up.
 *
 * <p>The classic format addresses four gigabytes and 65535 entries; past either, there is no length to
 * announce, and this says so rather than guess.
 */
public record ZipLayout(List<Entry> entries) {

    public record Entry(String name, long sizeBytes) {}

    static final int LOCAL_HEADER = 30;
    static final int DATA_DESCRIPTOR = 16;
    static final int CENTRAL_HEADER = 46;
    static final int END_RECORD = 22;
    static final long CLASSIC_LIMIT = 0xFFFFFFFFL;
    static final int MAX_ENTRIES = 0xFFFF;

    public Optional<Long> sizeBytes() {
        if (entries.size() >= MAX_ENTRIES) {
            return Optional.empty();
        }
        long local = 0;
        long central = 0;
        for (Entry entry : entries) {
            if (entry.sizeBytes() >= CLASSIC_LIMIT) {
                return Optional.empty();
            }
            int name = entry.name().getBytes(StandardCharsets.UTF_8).length;
            local += LOCAL_HEADER + name + entry.sizeBytes() + DATA_DESCRIPTOR;
            central += CENTRAL_HEADER + name;
        }
        if (local >= CLASSIC_LIMIT || central >= CLASSIC_LIMIT) {
            return Optional.empty();
        }
        return Optional.of(local + central + END_RECORD);
    }
}
