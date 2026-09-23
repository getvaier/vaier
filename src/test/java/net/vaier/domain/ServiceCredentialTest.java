package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both values end up in an HTTP header, so anything that could split or forge one is refused here. */
class ServiceCredentialTest {

    @Test
    void refusesWhatCannotRideInABasicAuthHeader() {
        record Row(String username, String password, String named) {}
        for (Row row : List.of(
                new Row(null, "pw", "username"),
                new Row("  ", "pw", "username"),
                // ':' is basic auth's separator: the service would split the username in two.
                new Row("tu:rid", "pw", "username"),
                new Row("turid\r\nX-Evil: 1", "pw", "username"),
                new Row("turid", "pw\nX-Evil: 1", "password"),
                new Row("turid", "pw\u0000", "password"),
                new Row("turid", null, "password"),
                new Row("turid", "", "password"),
                new Row("x".repeat(257), "pw", "username"),
                new Row("turid", "x".repeat(1025), "password"))) {
            assertThatThrownBy(() -> new ServiceCredential(row.username(), row.password()))
                .as("%s / %s", row.username(), row.password())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(row.named());
        }
    }

    @Test
    void rendersAsBasicAuthInUtf8_andNeverShowsThePasswordAsText() {
        ServiceCredential credential = new ServiceCredential("turid", "pässord med mellomrom");

        String header = credential.authorizationHeader();

        assertThat(header).startsWith("Basic ");
        assertThat(new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8))
            .isEqualTo("turid:pässord med mellomrom");
        assertThat(credential.toString()).contains("turid").doesNotContain("pässord");
    }
}
