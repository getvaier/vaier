package net.vaier.adapter.driven;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvLicenseTokenAdapterTest {

    @Test
    void presentTokenIsReturnedTrimmed() {
        assertThat(new EnvLicenseTokenAdapter("  payload.signature  ").readToken())
            .contains("payload.signature");
    }

    @Test
    void blankOrNullTokenIsEmpty() {
        assertThat(new EnvLicenseTokenAdapter("   ").readToken()).isEmpty();
        assertThat(new EnvLicenseTokenAdapter(null).readToken()).isEmpty();
    }
}
