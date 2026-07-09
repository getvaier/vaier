package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveTest {

    @Test
    void parseListReadsBorgJson() {
        // The shape borg emits for `borg list --json`: an "archives" array of {archive,id,time}.
        String json = """
            {
              "archives": [
                { "archive": "colina-2024-06-01T12:00:00", "id": "abc123", "time": "2024-06-01T12:00:00.000000" },
                { "archive": "colina-2024-06-02T12:00:00", "id": "def456", "time": "2024-06-02T12:00:00.000000" }
              ]
            }
            """;

        List<Archive> archives = Archive.parseList(json);

        assertThat(archives).hasSize(2);
        assertThat(archives.get(0).name()).isEqualTo("colina-2024-06-01T12:00:00");
        assertThat(archives.get(0).id()).isEqualTo("abc123");
        assertThat(archives.get(0).time()).isEqualTo(Instant.parse("2024-06-01T12:00:00Z"));
        assertThat(archives.get(1).name()).isEqualTo("colina-2024-06-02T12:00:00");
        assertThat(archives.get(1).id()).isEqualTo("def456");
    }

    @Test
    void parseListEmptyOnUnparseable() {
        // Never throws on garbage or an unexpected shape — mirrors RemoteDiskUsage.parse's stance.
        assertThat(Archive.parseList("not json at all")).isEmpty();
        assertThat(Archive.parseList("{ \"archives\": \"not-an-array\" }")).isEmpty();
        assertThat(Archive.parseList("{ \"unexpected\": 1 }")).isEmpty();
    }

    @Test
    void parseListEmptyOnBlank() {
        assertThat(Archive.parseList(null)).isEmpty();
        assertThat(Archive.parseList("")).isEmpty();
        assertThat(Archive.parseList("   ")).isEmpty();
    }
}
