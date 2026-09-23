package net.vaier.adapter.driven;

import net.vaier.domain.FirstRunPassword;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** dex-init writes the file: the email on the first line, the password on the second. */
class FirstRunPasswordFileAdapterTest {

    @Test
    void read_parsesTheTwoLineFile_andIsEmptyWithoutAWholeOne(@TempDir Path dir) throws Exception {
        FirstRunPasswordFileAdapter adapter = new FirstRunPasswordFileAdapter(dir.toString());
        record Row(String label, String content, Optional<FirstRunPassword> expected) {}
        for (Row row : List.of(
                new Row("absent", null, Optional.empty()),
                new Row("whole", "you@example.com\nabc-def\n", Optional.of(new FirstRunPassword("you@example.com", "abc-def"))),
                new Row("one line", "you@example.com\n", Optional.empty()),
                new Row("blank", "\n\n", Optional.empty()))) {
            Path file = dir.resolve("first-run-password");
            Files.deleteIfExists(file);
            if (row.content() != null) Files.writeString(file, row.content());
            assertThat(adapter.read()).as(row.label()).isEqualTo(row.expected());
        }
    }
}
