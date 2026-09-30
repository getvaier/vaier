package net.vaier.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the <b>stamped host</b> onto {@code VaierSetup.exe}: a trailer appended after the exe's last byte,
 * {@code [host UTF-8][host length, uint32 little-endian]["VAIERHOST1"]}. Windows ignores bytes past the
 * image, so one build serves every Vaier; the installer reads the trailer and presets the address.
 *
 * <p>Streams in, stream out, so a 54 MB download is never held in memory. Nothing here touches a file.
 */
public final class WindowsAppStamp {

    /** Half of a contract with the Windows app, which looks for this at the very end of its own file. */
    private static final byte[] MAGIC = "VAIERHOST1".getBytes(StandardCharsets.US_ASCII);
    private static final int LENGTH_BYTES = Integer.BYTES;
    private static final int MAX_HOST_BYTES = 253;
    private static final int MAX_TRAILER = MAX_HOST_BYTES + LENGTH_BYTES + MAGIC.length;

    private WindowsAppStamp() {
    }

    /**
     * Copies {@code exe} to {@code out} with {@code host} appended, replacing any earlier stamp. False when
     * {@code exe} is empty — there is nothing to stamp, and what was written to {@code out} is void.
     */
    public static boolean stampedWith(InputStream exe, OutputStream out, String host) throws IOException {
        byte[] hostBytes = hostBytes(host);
        // The last MAX_TRAILER bytes are held back until the end: they may be an earlier stamp to drop.
        byte[] buffer = new byte[64 * 1024 + MAX_TRAILER];
        int held = 0;
        long total = 0;
        for (int read = exe.read(buffer, held, buffer.length - held); read >= 0;
             read = exe.read(buffer, held, buffer.length - held)) {
            held += read;
            total += read;
            if (held > MAX_TRAILER) {
                out.write(buffer, 0, held - MAX_TRAILER);
                System.arraycopy(buffer, held - MAX_TRAILER, buffer, 0, MAX_TRAILER);
                held = MAX_TRAILER;
            }
        }
        if (total == 0) {
            return false;
        }
        out.write(buffer, 0, held - earlierStampLength(buffer, held, total));
        out.write(hostBytes);
        out.write(ByteBuffer.allocate(LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(hostBytes.length).array());
        out.write(MAGIC);
        return true;
    }

    private static byte[] hostBytes(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A stamped host has to be a host");
        }
        byte[] bytes = host.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HOST_BYTES) {
            throw new IllegalArgumentException("A stamped host is at most " + MAX_HOST_BYTES + " bytes");
        }
        return bytes;
    }

    /** How many of the {@code held} tail bytes are a valid earlier stamp; 0 when they are the exe's own. */
    private static int earlierStampLength(byte[] tail, int held, long total) {
        int fixed = LENGTH_BYTES + MAGIC.length;
        if (held < fixed || !Arrays.equals(tail, held - MAGIC.length, held, MAGIC, 0, MAGIC.length)) {
            return 0;
        }
        int length = ByteBuffer.wrap(tail, held - fixed, LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length <= 0 || length > MAX_HOST_BYTES || length > total - fixed) {
            return 0;
        }
        return length + fixed;
    }
}
