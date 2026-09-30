package net.vaier.domain;

import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Windows <b>Vaier app</b> as Vaier knows it: the installer this deployment can hand a Windows computer.
 * Present or absent, never a half state — a zero-byte exe is a build that went wrong, not an app. The
 * bytes stream straight into the response, as the Android package's do.
 */
public record WindowsApp(long sizeBytes, Consumer<OutputStream> writer) {

    /** The one name the app is ever served under. */
    public static final String FILE_NAME = "VaierSetup.exe";

    public static final String CONTENT_TYPE = "application/vnd.microsoft.portable-executable";

    public WindowsApp {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("A Windows app with no bytes is not an app to serve");
        }
        if (writer == null) {
            throw new IllegalArgumentException("A Windows app needs something to stream its bytes from");
        }
    }

    /** The app an installer of {@code sizeBytes} bytes is, or empty when there is nothing to offer. */
    public static Optional<WindowsApp> of(long sizeBytes, Consumer<OutputStream> writer) {
        return sizeBytes > 0 && writer != null
            ? Optional.of(new WindowsApp(sizeBytes, writer))
            : Optional.empty();
    }

    public String contentDisposition() {
        return "attachment; filename=\"" + FILE_NAME + "\"";
    }

    public void writeTo(OutputStream out) {
        writer.accept(out);
    }
}
