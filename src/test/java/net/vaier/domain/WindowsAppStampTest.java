package net.vaier.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.vaier.testsupport.SyntheticZip;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * The Windows app's <b>stamped host</b> is one extra file beside {@code Vaier.exe} in the download. The
 * app reads it on first launch and presets the address, so it never asks a person to type one.
 */
class WindowsAppStampTest {

    private static final String HOST = "vaier.example.com";

    private static byte[] stamped(byte[] zip, String host) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(WindowsAppStamp.stampedWith(new ByteArrayInputStream(zip), out, host)).isTrue();
        return out.toByteArray();
    }

    @Test
    void theDownloadCarriesEveryFileItWasBuiltWith_plusTheHostBesideVaierExe() throws IOException {
        Map<String, String> out = SyntheticZip.entries(stamped(SyntheticZip.windowsApp(), HOST));

        assertThat(out).containsAllEntriesOf(SyntheticZip.entries(SyntheticZip.windowsApp()));
        assertThat(out).contains(entry("Vaier/stamped-host.txt", HOST)).hasSize(3);
    }

    @Test
    void stampingAgainReplacesTheHost_neverRepeatsIt() throws IOException {
        byte[] twice = stamped(stamped(SyntheticZip.windowsApp(), "vaier.old.example"), HOST);

        Map<String, String> out = SyntheticZip.entries(twice);
        assertThat(out).contains(entry("Vaier/stamped-host.txt", HOST)).hasSize(3);
    }

    @Test
    void somethingThatIsNotAZipCannotBeStamped() throws IOException {
        // Served as built by the caller rather than turned into a zip holding nothing but the host.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] notAZip = "MZ just an exe".getBytes(StandardCharsets.UTF_8);

        assertThat(WindowsAppStamp.stampedWith(new ByteArrayInputStream(notAZip), out, HOST)).isFalse();
    }

    @Test
    void aStampedHostHasToBeAHost() {
        for (String blank : new String[] { null, "", "  " }) {
            assertThatThrownBy(() -> WindowsAppStamp.stampedWith(
                new ByteArrayInputStream(SyntheticZip.windowsApp()), new ByteArrayOutputStream(), blank))
                .as(String.valueOf(blank)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
