package net.vaier.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalWebSocketConfigTest {

    @Test
    void allowlist_includesTheVaierOrigin_andLocalDev_notWildcard() {
        List<String> patterns = TerminalWebSocketConfig.allowedOriginPatterns("example.com");

        assertThat(patterns).contains(
            "https://vaier.example.com",
            "http://localhost:*",
            "http://127.0.0.1:*");
        // A proper same-origin allowlist — never a match-everything pattern, never an arbitrary origin.
        assertThat(patterns).doesNotContain("*");
        assertThat(patterns).doesNotContain("https://evil.example");
    }

    @Test
    void allowlist_blankOrNullDomain_stillNeverMatchesEverything() {
        for (String domain : new String[]{null, "", "  "}) {
            List<String> patterns = TerminalWebSocketConfig.allowedOriginPatterns(domain);
            // Graceful fallback: local dev still works, but there is no Vaier origin and no wildcard.
            assertThat(patterns).contains("http://localhost:*", "http://127.0.0.1:*");
            assertThat(patterns).doesNotContain("*");
            assertThat(patterns).noneMatch(p -> p.startsWith("https://vaier."));
        }
    }

    @Test
    void allowlist_trimsDomain() {
        assertThat(TerminalWebSocketConfig.allowedOriginPatterns("  example.com  "))
            .contains("https://vaier.example.com");
    }
}
