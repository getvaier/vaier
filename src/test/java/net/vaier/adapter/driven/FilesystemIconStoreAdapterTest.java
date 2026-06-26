package net.vaier.adapter.driven;

import net.vaier.domain.Icon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FilesystemIconStoreAdapterTest {

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    }

    private static FilesystemIconStoreAdapter adapterAt(Path dir) {
        FilesystemIconStoreAdapter adapter = new FilesystemIconStoreAdapter();
        adapter.setCacheDir(dir.toString());
        adapter.init();
        return adapter;
    }

    @Test
    void storeThenLoad_roundTripsBytesAndContentType(@TempDir Path dir) {
        FilesystemIconStoreAdapter adapter = adapterAt(dir);
        Icon icon = new Icon(png(), "image/png");

        adapter.store("grafana.example.com", icon);
        Optional<Icon> loaded = adapter.load("grafana.example.com");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().body()).isEqualTo(png());
        assertThat(loaded.get().contentType()).isEqualTo("image/png");
    }

    @Test
    void load_returnsEmptyForUnknownKey(@TempDir Path dir) {
        FilesystemIconStoreAdapter adapter = adapterAt(dir);

        assertThat(adapter.load("never.stored.example.com")).isEmpty();
    }

    @Test
    void store_thenLoadDifferentKey_returnsEmpty(@TempDir Path dir) {
        FilesystemIconStoreAdapter adapter = adapterAt(dir);
        adapter.store("a.example.com", new Icon(png(), "image/png"));

        assertThat(adapter.load("b.example.com")).isEmpty();
    }

    @Test
    void load_returnsEmptyWhenCacheDirMissingOrUnwritable(@TempDir Path dir) {
        // Point at a path under a file (which cannot become a directory) so init() disables
        // the store. load() must return empty and store() must not throw — best-effort caching.
        Path unwritable = dir.resolve("a-regular-file/subdir");
        FilesystemIconStoreAdapter adapter = new FilesystemIconStoreAdapter();
        adapter.setCacheDir(unwritable.toString());
        // Create the parent as a regular file so the directory cannot be created.
        assertThatCode(() -> java.nio.file.Files.writeString(
                dir.resolve("a-regular-file"), "x")).doesNotThrowAnyException();
        adapter.init();

        assertThat(adapter.load("grafana.example.com")).isEmpty();
        assertThatCode(() -> adapter.store("grafana.example.com", new Icon(png(), "image/png")))
                .doesNotThrowAnyException();
        assertThat(adapter.load("grafana.example.com")).isEmpty();
    }
}
