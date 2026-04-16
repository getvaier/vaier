package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WireGuardVpnAdapterTest {

    @Test
    void extractValue_findsKeyWithSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey = abc123\nAddress = 10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void extractValue_findsKeyWithNoSpacesAroundEquals() {
        String config = "[Interface]\nPrivateKey=abc123\nAddress=10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEqualTo("abc123");
    }

    @Test
    void extractValue_returnsEmptyStringForMissingKey() {
        String config = "[Interface]\nAddress = 10.13.13.2/32\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "PrivateKey")).isEmpty();
    }

    @Test
    void extractValue_doesNotMatchPartialKeyName() {
        String config = "PresharedKey = xyz789\n";
        assertThat(WireGuardVpnAdapter.extractValue(config, "Key")).isEmpty();
    }
}
