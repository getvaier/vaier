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
 * {@code windows/dist/VaierSetup.exe} into {@code /app/windows/} when the build tree has one. What is
 * handed out is the installer with this deployment's host stamped in, kept on disk beside the source.
 */
@Component
@Slf4j
public class FilesystemWindowsAppAdapter implements ForReadingWindowsApp {

    private final String setupPath;
    private final KeptStampedCopy stampedCopy = new KeptStampedCopy(FilesystemWindowsAppAdapter::stamp);

    public FilesystemWindowsAppAdapter(@Value("${vaier.windows.setup:/app/windows/VaierSetup.exe}") String setupPath) {
        this.setupPath = setupPath;
    }

    @Override
    public Optional<WindowsApp> readApp(String servedHost) {
        if (setupPath == null || setupPath.isBlank()) {
            return Optional.empty();
        }
        Path source = Path.of(setupPath);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            Path toServe = servedHost == null || servedHost.isBlank()
                ? source
                : stampedCopy.servedCopyOf(source, servedHost);
            return WindowsApp.of(Files.size(toServe), out -> copy(toServe, out));
        } catch (IOException e) {
            log.debug("No Windows app to serve from {}: {}", setupPath, e.getMessage());
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
        log.warn("The Windows app at {} is empty, so it cannot carry {}; serving it as built", source, host);
        return false;
    }

    private static void copy(Path exe, OutputStream out) {
        try {
            Files.copy(exe, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
