package net.vaier.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vaier's own static assets are hand-authored source, so a raw control character in one of them is never
 * intentional — it is an editor or a paste that went wrong. Such a byte is invisible in an editor, makes the
 * file register as <em>binary</em> to grep (so it silently drops out of every code search), and is one
 * transcoding away from corrupting the surrounding expression. Control characters belong in source as escapes
 * ({@code \x1b}, {@code \x00}), never as the byte itself.
 *
 * <p>Vendored third-party bundles and binary assets are excluded — we do not author those.
 */
class StaticAssetsTest {

    private static final Path STATIC_ROOT = Path.of("src/main/resources/static");

    /**
     * Tab, newline and carriage return are the only control characters that legitimately appear in source.
     * DEL (0x7f) is included because it is every bit as invisible as the C0 range and reaches a file the same
     * way — a {@code \x7f} escape that lost its backslash.
     */
    private static boolean isForbidden(char c) {
        return (c < 0x20 || c == 0x7f) && c != '\t' && c != '\n' && c != '\r';
    }

    private static List<Path> ownSources() throws IOException {
        try (Stream<Path> tree = Files.walk(STATIC_ROOT)) {
            return tree.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(STATIC_ROOT.resolve("vendor")))
                .filter(p -> !p.startsWith(STATIC_ROOT.resolve("fonts")))
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".js") || n.endsWith(".css") || n.endsWith(".html");
                })
                .toList();
        }
    }

    @Test
    void ownStaticSources_carryNoRawControlCharacters() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path source : ownSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int line = 1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    line++;
                } else if (isForbidden(c)) {
                    offences.add("%s:%d holds a raw control character (0x%02x) — write it as an escape"
                        .formatted(source, line, (int) c));
                }
            }
        }

        assertThat(offences).isEmpty();
    }
}
