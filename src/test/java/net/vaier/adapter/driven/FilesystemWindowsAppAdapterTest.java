package net.vaier.adapter.driven;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vaier.domain.WindowsApp;
import net.vaier.domain.WindowsAppStamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Windows app is {@code VaierSetup.exe} in the image, served stamped with this Vaier's host and kept
 * stamped on disk beside the source. When to cut the stamp again is {@link FilesystemAndroidAppAdapterTest}'s
 * to prove — both adapters keep their copy the same way.
 */
class FilesystemWindowsAppAdapterTest {

    private static final String HOST = "vaier.example.com";
    private static final byte[] EXE = "MZ not really an exe".getBytes(StandardCharsets.UTF_8);

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
    void theExeIsServedStampedWithTheHostThatServedIt_andKeptBesideTheSource() throws IOException {
        Path exe = Files.write(dir.resolve("VaierSetup.exe"), EXE);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        WindowsAppStamp.stampedWith(new ByteArrayInputStream(EXE), expected, HOST);

        byte[] out = served(new FilesystemWindowsAppAdapter(exe.toString()).readApp(HOST));

        assertThat(out).isEqualTo(expected.toByteArray());
        assertThat(dir.resolve("VaierSetup.exe.stamped")).hasBinaryContent(out);
        assertThat(exe).as("the source is left exactly as the build made it").hasBinaryContent(EXE);
    }

    @Test
    void noExeToServe_isAnEmptyAnswer() throws IOException {
        Path missing = dir.resolve("missing.exe");
        Path directory = Files.createDirectory(dir.resolve("a-directory.exe"));
        Path empty = Files.createFile(dir.resolve("empty.exe"));

        for (String path : new String[] { missing.toString(), directory.toString(), empty.toString(), "  " }) {
            assertThat(new FilesystemWindowsAppAdapter(path).readApp(HOST)).as(path).isEmpty();
        }
    }

    @Test
    void noHostYet_isServedAsBuilt() throws IOException {
        // An app that has to be told an address beats no app at all.
        Path exe = Files.write(dir.resolve("VaierSetup.exe"), EXE);

        for (String host : new String[] { null, " " }) {
            assertThat(served(new FilesystemWindowsAppAdapter(exe.toString()).readApp(host)))
                .as(String.valueOf(host)).isEqualTo(EXE);
        }
    }

    @Test
    void theDefaultPathIsWhereTheImageActuallyPutsTheExe() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String adapter = Files.readString(
            Path.of("src/main/java/net/vaier/adapter/driven/FilesystemWindowsAppAdapter.java"));

        assertThat(adapter).contains("${vaier.windows.setup:/app/windows/VaierSetup.exe}");
        assertThat(dockerfile).contains("cp /tmp/windows/dist/VaierSetup.exe /app/windows/");
    }
}
