package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorTest {

    @Test
    void mapsBadGatewayToFriendlyTitleAndMessage() {
        GatewayError error = GatewayError.forStatus(502);

        assertThat(error.status()).isEqualTo(502);
        assertThat(error.title()).isNotBlank();
        assertThat(error.message()).isNotBlank();
        // A 502 is a backend-down situation — the message should reassure, not blame the visitor.
        assertThat(error.message().toLowerCase()).contains("unavailable");
    }

    @Test
    void mapsServiceUnavailableAndGatewayTimeoutToTheirOwnTitles() {
        assertThat(GatewayError.forStatus(503).title())
            .isNotEqualTo(GatewayError.forStatus(502).title());
        assertThat(GatewayError.forStatus(504).title())
            .isNotEqualTo(GatewayError.forStatus(502).title());
        assertThat(GatewayError.forStatus(503).status()).isEqualTo(503);
        assertThat(GatewayError.forStatus(504).status()).isEqualTo(504);
    }

    @Test
    void unknownStatusFallsBackToGenericMessageButKeepsTheStatus() {
        GatewayError error = GatewayError.forStatus(418);

        assertThat(error.status()).isEqualTo(418);
        assertThat(error.title()).isNotBlank();
        assertThat(error.message()).isNotBlank();
        // Falls back to the same generic copy as the default (502) family.
        assertThat(error.title()).isEqualTo(GatewayError.forStatus(502).title());
    }
}
