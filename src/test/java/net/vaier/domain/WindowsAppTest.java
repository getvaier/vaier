package net.vaier.domain;

import java.io.OutputStream;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** The Windows download, present or absent, never a half state — the same rule as the Android package. */
class WindowsAppTest {

    private static final Consumer<OutputStream> WRITER = out -> {
    };

    @Test
    void onlyAnExeWithBytesAndSomethingToStreamThemFrom_isAnAppToServe() {
        assertThat(WindowsApp.of(46_000_000L, WRITER)).hasValueSatisfying(
            app -> assertThat(app.sizeBytes()).isEqualTo(46_000_000L));
        assertThat(WindowsApp.of(0L, WRITER)).as("a zero-byte exe is a build that went wrong").isEmpty();
        assertThat(WindowsApp.of(-1L, WRITER)).isEmpty();
        assertThat(WindowsApp.of(10L, null)).isEmpty();
    }

    @Test
    void theAppIsAlwaysServedAsOneInstallerUnderOneName() {
        assertThat(WindowsApp.CONTENT_TYPE).isEqualTo("application/vnd.microsoft.portable-executable");
        assertThat(WindowsApp.of(10L, WRITER).orElseThrow().contentDisposition())
            .isEqualTo("attachment; filename=\"VaierSetup.exe\"");
    }
}
