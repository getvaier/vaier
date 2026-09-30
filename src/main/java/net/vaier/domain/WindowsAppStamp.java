package net.vaier.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Writes the <b>stamped host</b> into the Windows app's zip: one extra file, {@link #ENTRY}, beside
 * {@code Vaier.exe}, holding the host name of the Vaier that served it. The app reads it on first launch
 * and presets the address. A zip carries no signature, so unlike {@link ApkStamp} this is a plain entry.
 *
 * <p>Streams in, stream out, so a 46 MB download is never held in memory. Nothing here touches a file.
 */
public final class WindowsAppStamp {

    /** Half of a contract with the Windows app, which reads this file beside its own exe. */
    public static final String ENTRY = "Vaier/stamped-host.txt";

    private WindowsAppStamp() {
    }

    /**
     * Copies {@code zip} to {@code out} with {@code host} stamped in, replacing any earlier stamp. False
     * when {@code zip} holds no entries — it is not a zip, and what was written to {@code out} is void.
     */
    public static boolean stampedWith(InputStream zip, OutputStream out, String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A stamped host has to be a host");
        }
        ZipInputStream source = new ZipInputStream(zip);
        ZipOutputStream stamped = new ZipOutputStream(out);
        boolean anyEntry = false;
        for (ZipEntry entry = source.getNextEntry(); entry != null; entry = source.getNextEntry()) {
            anyEntry = true;
            if (entry.getName().equals(ENTRY)) {
                continue;
            }
            ZipEntry copy = new ZipEntry(entry.getName());
            copy.setLastModifiedTime(entry.getLastModifiedTime());
            stamped.putNextEntry(copy);
            source.transferTo(stamped);
            stamped.closeEntry();
        }
        if (!anyEntry) {
            return false;
        }
        stamped.putNextEntry(new ZipEntry(ENTRY));
        stamped.write(host.getBytes(StandardCharsets.UTF_8));
        stamped.closeEntry();
        stamped.finish();
        return true;
    }
}
