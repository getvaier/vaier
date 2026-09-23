package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both values are spliced into a root shell's heredocs and Dex's YAML, so anything outside a strict
 * charset is refused here, before it is ever written.
 */
class ProviderCredentialsTest {

    @Test
    void acceptsWhatGoogleAndGithubIssue_andRefusesAnythingThatCouldBreakOutOfTheRender() {
        assertThat(new ProviderCredentials("123-abc.apps.googleusercontent.com", "GOCSPX-a_b.c").clientId())
            .isEqualTo("123-abc.apps.googleusercontent.com");
        assertThat(new ProviderCredentials(" Ov23liAbC ", "0123456789abcdef0123456789abcdef01234567").clientId())
            .as("pasted whitespace is trimmed, not refused").isEqualTo("Ov23liAbC");

        record Row(String clientId, String clientSecret, String named) {}
        for (Row row : List.of(
                new Row("", "secret", "client id"),
                new Row(null, "secret", "client id"),
                new Row("id", "  ", "client secret"),
                new Row("id $(reboot)", "secret", "client id"),
                new Row("id", "sec\nret", "client secret"),
                new Row("id", "a\"b", "client secret"),
                new Row("id}", "secret", "client id"),
                new Row("id", "x".repeat(257), "client secret"))) {
            assertThatThrownBy(() -> new ProviderCredentials(row.clientId(), row.clientSecret()))
                .as("%s / %s", row.clientId(), row.clientSecret())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(row.named());
        }
    }
}
