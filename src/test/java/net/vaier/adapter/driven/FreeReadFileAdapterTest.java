package net.vaier.adapter.driven;

import net.vaier.domain.FreeReads;
import net.vaier.domain.ServiceCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FreeReadFileAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsThroughAFile_andAnAbsentOrBrokenFileIsNoFreeReads() throws Exception {
        assertThat(new FreeReadFileAdapter(tempDir.toString()).read()).isEqualTo(FreeReads.empty());

        new FreeReadFileAdapter(tempDir.toString()).update(reads -> reads
            .alwaysAllowing("example.com", "/paperless", ServiceCall.proposed("GET", "/api/documents/", null))
            .alwaysAllowing("openhab.example.com", null, ServiceCall.proposed("GET", "/rest/items", null)));
        FreeReads saved = new FreeReadFileAdapter(tempDir.toString()).read();
        assertThat(saved.of("example.com", "/paperless")).containsExactly("/api/");
        assertThat(saved.of("openhab.example.com", null)).containsExactly("/rest/");

        Files.writeString(tempDir.resolve("free-reads.yml"), "services: [unclosed");
        assertThat(new FreeReadFileAdapter(tempDir.toString()).read()).isEqualTo(FreeReads.empty());
    }
}
