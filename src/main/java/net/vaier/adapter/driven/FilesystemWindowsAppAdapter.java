package net.vaier.adapter.driven;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.WindowsApp;
import net.vaier.domain.WindowsAppStamp;
import net.vaier.domain.port.ForReadingWindowsApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serves the Windows <b>Vaier app</b> off the image's filesystem: the Dockerfile copies
 * {@code windows/dist/Vaier-windows.zip} into {@code /app/windows/} when the build tree has one. What is
 * handed out is the zip with this deployment's host stamped in, kept on disk beside the source.
 */
@Component
@Slf4j
public class FilesystemWindowsAppAdapter implements ForReadingWindowsApp {

    private final String zipPath;
    private final KeptStampedCopy stampedCopy = new KeptStampedCopy(FilesystemWindowsAppAdapter::stamp);

    public FilesystemWindowsAppAdapter(@Value("${vaier.windows.zip:/app/windows/Vaier-windows.zip}") String zipPath) {
        this.zipPath = zipPath;
    }

    @Override
    public Optional<WindowsApp> readApp(String servedHost) {
        if (zipPath == null || zipPath.isBlank()) {
            return Optional.empty();
        }
        Path source = Path.of(zipPath);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            Path toServe = servedHost == null || servedHost.isBlank()
                ? source
                : stampedCopy.servedCopyOf(source, servedHost);
            return WindowsApp.of(Files.size(toServe), out -> copy(toServe, out));
        } catch (IOException e) {
            log.debug("No Windows app to serve from {}: {}", zipPath, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean stamp(Path source, Path target, String host) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            if (WindowsAppStamp.stampedWith(in, out, host)) {
                return true;
            }
        }
        log.warn("The Windows app at {} is not a zip, so it cannot carry {}; serving it as built", source, host);
        return false;
    }

    private static void copy(Path zip, OutputStream out) {
        try {
            Files.copy(zip, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
