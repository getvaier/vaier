package net.vaier.adapter.driven;

import net.vaier.domain.OpenServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenServiceStateFileAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsThroughAFile_andAnAbsentOrBrokenFileIsNothingOutstanding() throws Exception {
        assertThat(new OpenServiceStateFileAdapter(tempDir.toString()).read()).isEqualTo(OpenServiceState.empty());

        OpenServiceState state = new OpenServiceState(Set.of("rack-router"), Set.of("site-router"));
        new OpenServiceStateFileAdapter(tempDir.toString()).save(state);
        assertThat(new OpenServiceStateFileAdapter(tempDir.toString()).read()).isEqualTo(state);

        Files.writeString(tempDir.resolve("open-services.yml"), "notified: [unclosed");
        assertThat(new OpenServiceStateFileAdapter(tempDir.toString()).read()).isEqualTo(OpenServiceState.empty());
    }
}
