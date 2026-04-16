package net.vaier.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingConstantsTest {

    @Test
    void mandatorySubdomains_containsVaierAndLogin() {
        assertThat(PublishingConstants.MANDATORY_SUBDOMAINS).containsExactlyInAnyOrder("vaier", "login");
    }

    @Test
    void mandatorySubdomains_isImmutable() {
        assertThat(PublishingConstants.MANDATORY_SUBDOMAINS).isUnmodifiable();
    }
}
