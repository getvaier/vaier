package net.vaier.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Windows app's <b>stamped host</b> is a trailer appended to {@code VaierSetup.exe}:
 * {@code [host UTF-8][host length, uint32 little-endian]["VAIERHOST1"]}. The installer reads it and presets
 * the address, so it never asks a person to type one.
 */
class WindowsAppStampTest {

    private static final String HOST = "vaier.example.com";
    private static final byte[] EXE = "MZ not really an exe".getBytes(StandardCharsets.UTF_8);

    private static byte[] stamped(byte[] exe, String host) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(WindowsAppStamp.stampedWith(new ByteArrayInputStream(exe), out, host)).isTrue();
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }

    @Test
    void theExeIsServedByteForByte_withTheHostAppendedAsTheTrailerTheAppReads() throws IOException {
        // Half of a contract with windows/Vaier.Core/SetupStamp.cs: host, its length LE, then the magic.
        byte[] trailer = HexFormat.of().parseHex(
            "76616965722e6578616d706c652e636f6d" + "11000000" + "5641494552484f535431");

        assertThat(stamped(EXE, HOST)).isEqualTo(concat(EXE, trailer));
    }

    @Test
    void stampingAgainReplacesTheHost_neverRepeatsIt() throws IOException {
        byte[] big = new byte[100_000];
        new Random(7).nextBytes(big);
        for (byte[] exe : new byte[][] { EXE, big }) {
            assertThat(stamped(stamped(exe, "vaier.old.example"), HOST)).as(exe.length + " bytes")
                .isEqualTo(stamped(exe, HOST)).startsWith(exe);
        }
    }

    @Test
    void bytesThatOnlyLookLikeATrailer_areKeptAsPartOfTheExe() throws IOException {
        // The magic with a length no stamp can have is the exe's own bytes, not a stamp to strip.
        record Row(String why, byte[] exe) {
        }
        byte[] magic = "VAIERHOST1".getBytes(StandardCharsets.US_ASCII);
        for (Row row : new Row[] {
            new Row("zero length", concat(EXE, concat(new byte[] {0, 0, 0, 0}, magic))),
            new Row("longer than a host", concat(EXE, concat(new byte[] {(byte) 254, 0, 0, 0}, magic))),
            new Row("longer than the file", concat(new byte[] {'a'}, concat(new byte[] {2, 0, 0, 0}, magic)))}) {
            byte[] out = stamped(row.exe(), HOST);

            assertThat(out).as(row.why()).startsWith(row.exe()).hasSize(row.exe().length + HOST.length() + 14);
        }
    }

    @Test
    void anEmptySourceCannotBeStamped() throws IOException {
        // Served as built by the caller rather than turned into a file holding nothing but the host.
        assertThat(WindowsAppStamp.stampedWith(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), HOST))
            .isFalse();
    }

    @Test
    void aStampedHostHasToBeAHost() {
        for (String bad : new String[] { null, "", "  ", "a".repeat(254) }) {
            assertThatThrownBy(() -> WindowsAppStamp.stampedWith(
                new ByteArrayInputStream(EXE), new ByteArrayOutputStream(), bad))
                .as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
