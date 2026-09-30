package net.vaier.adapter.driven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vaier.domain.WindowsApp;
import net.vaier.testsupport.SyntheticZip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Windows app is a zip in the image, served stamped with this Vaier's host and kept stamped on disk
 * beside the source. When to cut the stamp again is {@link FilesystemAndroidAppAdapterTest}'s to prove —
 * both adapters keep their copy the same way.
 */
class FilesystemWindowsAppAdapterTest {

    private static final String HOST = "vaier.example.com";

    @TempDir
    Path dir;

    private static byte[] served(Optional<WindowsApp> app) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        app.orElseThrow().writeTo(sink);
        assertThat(app.get().sizeBytes()).as("the Content-Length matches the bytes that follow")
            .isEqualTo(sink.size());
        return sink.toByteArray();
    }

    @Test
    void theZipIsServedStampedWithTheHostThatServedIt_andKeptBesideTheSource() throws IOException {
        Path zip = dir.resolve("Vaier-windows.zip");
        Files.write(zip, SyntheticZip.windowsApp());

        byte[] out = served(new FilesystemWindowsAppAdapter(zip.toString()).readApp(HOST));

        assertThat(SyntheticZip.entries(out)).containsEntry("Vaier/stamped-host.txt", HOST);
        assertThat(dir.resolve("Vaier-windows.zip.stamped")).hasBinaryContent(out);
        assertThat(zip).as("the source is left exactly as the build made it")
            .hasBinaryContent(SyntheticZip.windowsApp());
    }

    @Test
    void noZipToServe_isAnEmptyAnswer() throws IOException {
        Path missing = dir.resolve("missing.zip");
        Path directory = Files.createDirectory(dir.resolve("a-directory.zip"));
        Path empty = Files.createFile(dir.resolve("empty.zip"));

        for (String path : new String[] { missing.toString(), directory.toString(), empty.toString(), "  " }) {
            assertThat(new FilesystemWindowsAppAdapter(path).readApp(HOST)).as(path).isEmpty();
        }
    }

    @Test
    void aZipThatCannotBeStamped_orNoHostYet_isServedAsBuilt() throws IOException {
        // An app that has to be told an address beats no app at all.
        record Row(byte[] source, String host) {
        }
        byte[] notAZip = "MZ just an exe".getBytes(StandardCharsets.UTF_8);
        for (Row row : new Row[] {
            new Row(notAZip, HOST), new Row(SyntheticZip.windowsApp(), null), new Row(SyntheticZip.windowsApp(), " ")}) {
            Path zip = Files.write(Files.createTempDirectory(dir, "row").resolve("Vaier-windows.zip"), row.source());

            assertThat(served(new FilesystemWindowsAppAdapter(zip.toString()).readApp(row.host())))
                .as(String.valueOf(row.host())).isEqualTo(row.source());
        }
    }

    @Test
    void theDefaultPathIsWhereTheImageActuallyPutsTheZip() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String adapter = Files.readString(
            Path.of("src/main/java/net/vaier/adapter/driven/FilesystemWindowsAppAdapter.java"));

        assertThat(adapter).contains("${vaier.windows.zip:/app/windows/Vaier-windows.zip}");
        assertThat(dockerfile).contains("cp /tmp/windows/dist/Vaier-windows.zip /app/windows/");
    }
}
