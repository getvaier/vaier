package net.vaier.application.service;

import net.vaier.domain.ZipLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer behind an announced download size: uncompressed entries, streamed, with the length the
 * {@link ZipLayout} promised — byte for byte — and readable by an ordinary unzip.
 */
class StoredZipTest {

    @TempDir
    Path dir;

    @Test
    void writesExactlyTheLengthTheLayoutPromised_andAnOrdinaryUnzipReadsItBack() throws Exception {
        byte[] a = "hello, photo".getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[70_000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        List<ZipLayout.Entry> entries = List.of(new ZipLayout.Entry("a.jpg", a.length), new ZipLayout.Entry("sub/ø.jpg", b.length));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StoredZip zip = new StoredZip(out, 1_700_000_000_000L);
        zip.entry("a.jpg", sink -> sink.write(a));
        zip.entry("sub/ø.jpg", sink -> sink.write(b));
        zip.finish();

        assertThat((long) out.size()).isEqualTo(new ZipLayout(entries).sizeBytes().orElseThrow());
        Path file = dir.resolve("t.zip");
        Files.write(file, out.toByteArray());
        try (ZipFile read = new ZipFile(file.toFile())) {
            assertThat(read.stream().map(ZipEntry::getName)).containsExactly("a.jpg", "sub/ø.jpg");
            ZipEntry second = read.getEntry("sub/ø.jpg");
            assertThat(second.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(second.getSize()).isEqualTo(b.length);
            assertThat(read.getInputStream(read.getEntry("a.jpg")).readAllBytes()).isEqualTo(a);
            assertThat(read.getInputStream(second).readAllBytes()).isEqualTo(b);
        }
    }

    @Test
    void anEmptyZipIsStillAZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new StoredZip(out, 1_700_000_000_000L).finish();

        assertThat(out.size()).isEqualTo(22);
    }
}
