package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseTest {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant BEFORE_EXPIRY = Instant.parse("2026-06-04T00:00:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2027-06-04T00:00:00Z");

    private static License enterprise(Instant expiresAt) {
        return new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, expiresAt, Set.of("lan-scanner"));
    }

    @Test
    void notExpiredBeforeExpiry() {
        assertThat(enterprise(EXPIRES).isExpired(BEFORE_EXPIRY)).isFalse();
        assertThat(enterprise(EXPIRES).isValidAt(BEFORE_EXPIRY)).isTrue();
    }

    @Test
    void expiredAfterExpiry() {
        assertThat(enterprise(EXPIRES).isExpired(AFTER_EXPIRY)).isTrue();
        assertThat(enterprise(EXPIRES).isValidAt(AFTER_EXPIRY)).isFalse();
    }

    @Test
    void aNullExpiryNeverExpires() {
        assertThat(enterprise(null).isExpired(AFTER_EXPIRY)).isFalse();
        assertThat(enterprise(null).isValidAt(AFTER_EXPIRY)).isTrue();
    }

    @Test
    void grantsEnterpriseOnlyWhenEnterpriseEditionAndValid() {
        assertThat(enterprise(EXPIRES).grantsEnterprise(BEFORE_EXPIRY)).isTrue();
        assertThat(enterprise(EXPIRES).grantsEnterprise(AFTER_EXPIRY)).isFalse();

        License community = new License("Acme Ltd", Edition.COMMUNITY, ISSUED, EXPIRES, Set.of());
        assertThat(community.grantsEnterprise(BEFORE_EXPIRY)).isFalse();
    }

    @Test
    void effectiveEditionFallsBackToCommunityWhenExpired() {
        assertThat(enterprise(EXPIRES).effectiveEdition(BEFORE_EXPIRY)).isEqualTo(Edition.ENTERPRISE);
        assertThat(enterprise(EXPIRES).effectiveEdition(AFTER_EXPIRY)).isEqualTo(Edition.COMMUNITY);
    }

    @Test
    void hasFeatureRequiresValidEnterpriseAndTheNamedFeature() {
        License license = enterprise(EXPIRES);
        assertThat(license.hasFeature("lan-scanner", BEFORE_EXPIRY)).isTrue();
        assertThat(license.hasFeature("unknown", BEFORE_EXPIRY)).isFalse();
        assertThat(license.hasFeature("lan-scanner", AFTER_EXPIRY)).isFalse();
    }
}
