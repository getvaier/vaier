package net.vaier.adapter.driven;

import net.vaier.domain.Memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Vaier's memory is one YAML file beside everything else it keeps. */
class MemoryFileAdapterTest {

    @TempDir
    Path configDir;

    private MemoryFileAdapter adapter() {
        return new MemoryFileAdapter(configDir.toString());
    }

    @Test
    void nothingKeptYetIsAnEmptyMemory() {
        assertThat(adapter().load().facts()).isEmpty();
    }

    @Test
    void aMemoryComesBackExactlyAsSaved() throws Exception {
        Memory saved = Memory.empty().remember("Photos live under /volume1/photo: \"the\" folder.\nTwo lines.", 1L)
            .remember("Colina 27 is the relay.", 2L);
        adapter().save(saved);

        assertThat(adapter().load()).isEqualTo(saved);
        assertThat(Files.exists(configDir.resolve("memory.yml"))).isTrue();
    }

    @Test
    void aDamagedFileReadsAsNothingKept() throws Exception {
        Files.writeString(configDir.resolve("memory.yml"), "facts: [ {id: 1} ]\n: : :\n");

        assertThat(adapter().load().facts()).isEmpty();
    }
}
