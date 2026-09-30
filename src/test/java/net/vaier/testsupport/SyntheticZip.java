package net.vaier.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** A miniature Windows app download, built rather than checked in, and a reader for what came out. */
public final class SyntheticZip {

    private SyntheticZip() {
    }

    /** The zip {@code windows/build.sh} makes, in miniature: everything under one {@code Vaier/} folder. */
    public static byte[] windowsApp() {
        return of(Map.of("Vaier/Vaier.exe", "MZ not really an exe", "Vaier/tunnel.dll", "MZ nor a dll"));
    }

    public static byte[] of(Map<String, String> entries) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every entry's name and text, in the order the zip holds them. */
    public static Map<String, String> entries(byte[] zipBytes) {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
