package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.ApkStamp;
import net.vaier.domain.port.ForReadingAndroidApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serves the <b>Vaier app</b> straight off the image's filesystem: the Dockerfile copies {@code apk/}
 * into {@code /app/apk/}, so a build from a tree that has the package carries it and a build without one
 * carries an empty directory.
 *
 * <p>What is handed out is the package with this deployment's host name stamped into its signing block —
 * the domain cuts the stamp, this only decides where the result lives. It lives <b>on disk beside the
 * source</b>, as {@code vaier.apk.stamped}: the host is fixed for the life of the deployment, so the
 * stamp is cut once, and the package is around 20 MB, which is a second copy Vaier is not willing to
 * pin in the heap for the life of the process just to avoid writing a file. Downloads still stream.
 * A source whose size or timestamp has moved — an upgrade dropped a new package in — is stamped afresh.
 *
 * <p>Nothing else is read here but the file's size, and every way this can go wrong ends in the app being
 * served rather than withheld: a package with no signing block, or a directory that cannot be written to,
 * is served exactly as it was built, with one line in the log saying so. Trouble reading the source at
 * all is an app Vaier cannot serve, which is the same answer as having none.
 */
@Component
@Slf4j
public class FilesystemAndroidAppAdapter implements ForReadingAndroidApp {

    private final String apkPath;
    private final KeptStampedCopy stampedCopy = new KeptStampedCopy(FilesystemAndroidAppAdapter::stamp);

    public FilesystemAndroidAppAdapter(@Value("${vaier.android.apk:/app/apk/vaier.apk}") String apkPath) {
        this.apkPath = apkPath;
    }

    @Override
    public Optional<AndroidApp> readApp(String servedHost) {
        if (apkPath == null || apkPath.isBlank()) {
            return Optional.empty();
        }
        Path source = Path.of(apkPath);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            Path toServe = servedHost == null || servedHost.isBlank()
                ? source
                : stampedCopy.servedCopyOf(source, servedHost);
            return AndroidApp.of(Files.size(toServe), out -> copy(toServe, out));
        } catch (IOException e) {
            log.debug("No Android app to serve from {}: {}", apkPath, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean stamp(Path source, Path target, String servedHost) throws IOException {
        Optional<byte[]> stamped = ApkStamp.stampedWith(Files.readAllBytes(source), servedHost);
        if (stamped.isEmpty()) {
            log.warn("The Vaier app at {} has no APK Signing Block, so it cannot carry {}; serving it as "
                + "built — the app will have to be told this Vaier's address by hand", source, servedHost);
            return false;
        }
        Files.write(target, stamped.get());
        return true;
    }

    private void copy(Path apk, OutputStream out) {
        try {
            Files.copy(apk, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
