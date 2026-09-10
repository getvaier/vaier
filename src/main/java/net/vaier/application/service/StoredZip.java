package net.vaier.application.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A zip written uncompressed, one entry at a time, straight to a stream — the writer behind a download that
 * announces its length ({@code ZipLayout}). Each entry is a local header, the bytes as they come, and a
 * data descriptor carrying the CRC and size learnt on the way; the central directory follows at the end.
 * Byte for byte the length {@code ZipLayout} computes, which is the whole point of it.
 *
 * <p>Java's own {@code ZipOutputStream} cannot do this: an uncompressed entry there must know its CRC
 * before the first byte, which would mean reading every file twice.
 */
final class StoredZip {

    /** Writes one entry's bytes into the zip. */
    interface Source {
        void writeTo(OutputStream out) throws IOException;
    }

    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int DESCRIPTOR_SIGNATURE = 0x08074b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int END_SIGNATURE = 0x06054b50;
    private static final int VERSION = 20;
    /** Bit 3: sizes and CRC follow the data. Bit 11: names are UTF-8. */
    private static final int FLAGS = 0x0808;
    private static final int STORED = 0;

    private record Written(byte[] name, long crc, long size, long offset) {}

    private final OutputStream out;
    private final int dosTime;
    private final int dosDate;
    private final List<Written> written = new ArrayList<>();
    private long position;

    StoredZip(OutputStream out, long nowEpochMs) {
        this.out = out;
        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMs), ZoneId.systemDefault());
        this.dosTime = (now.getHour() << 11) | (now.getMinute() << 5) | (now.getSecond() / 2);
        this.dosDate = ((Math.max(1980, now.getYear()) - 1980) << 9) | (now.getMonthValue() << 5) | now.getDayOfMonth();
    }

    void entry(String name, Source source) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        long offset = position;
        int32(LOCAL_SIGNATURE);
        int16(VERSION);
        int16(FLAGS);
        int16(STORED);
        int16(dosTime);
        int16(dosDate);
        int32(0);            // crc, in the descriptor
        int32(0);            // compressed size, in the descriptor
        int32(0);            // size, in the descriptor
        int16(nameBytes.length);
        int16(0);            // no extra field
        raw(nameBytes);

        CRC32 crc = new CRC32();
        long[] count = {0};
        source.writeTo(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                crc.update(b);
                count[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                crc.update(b, off, len);
                count[0] += len;
            }
        });
        position += count[0];

        int32(DESCRIPTOR_SIGNATURE);
        int32((int) crc.getValue());
        int32((int) count[0]);
        int32((int) count[0]);
        written.add(new Written(nameBytes, crc.getValue(), count[0], offset));
    }

    void finish() throws IOException {
        long centralStart = position;
        for (Written w : written) {
            int32(CENTRAL_SIGNATURE);
            int16(VERSION);
            int16(VERSION);
            int16(FLAGS);
            int16(STORED);
            int16(dosTime);
            int16(dosDate);
            int32((int) w.crc());
            int32((int) w.size());
            int32((int) w.size());
            int16(w.name().length);
            int16(0);        // extra
            int16(0);        // comment
            int16(0);        // disk
            int16(0);        // internal attributes
            int32(0);        // external attributes
            int32((int) w.offset());
            raw(w.name());
        }
        long centralSize = position - centralStart;
        int32(END_SIGNATURE);
        int16(0);
        int16(0);
        int16(written.size());
        int16(written.size());
        int32((int) centralSize);
        int32((int) centralStart);
        int16(0);            // comment
        out.flush();
    }

    private void int16(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        position += 2;
    }

    private void int32(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
        position += 4;
    }

    private void raw(byte[] bytes) throws IOException {
        out.write(bytes);
        position += bytes.length;
    }
}
