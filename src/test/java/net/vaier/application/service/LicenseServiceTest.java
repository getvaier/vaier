package net.vaier.application.service;

import net.vaier.application.GetLicenseStatusUseCase.LicenseStatus;
import net.vaier.domain.Edition;
import net.vaier.domain.License;
import net.vaier.domain.port.ForReadingLicenseToken;
import net.vaier.domain.port.ForVerifyingLicense;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseServiceTest {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-04T00:00:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2027-06-04T00:00:00Z");

    private final Clock now = Clock.fixed(NOW, ZoneOffset.UTC);

    private static License enterprise() {
        return new License("Acme Ltd", Edition.ENTERPRISE, ISSUED, EXPIRES, Set.of("lan-scanner"));
    }

    private LicenseService service(Optional<String> token, Optional<License> verified, Clock clock) {
        ForReadingLicenseToken reader = () -> token;
        ForVerifyingLicense verifier = t -> verified;
        return new LicenseService(reader, verifier, clock);
    }

    @Test
    void noTokenInstalled_isCommunity() {
        LicenseService service = service(Optional.empty(), Optional.empty(), now);

        assertThat(service.currentEdition()).isEqualTo(Edition.COMMUNITY);
        LicenseStatus status = service.status();
        assertThat(status.licensed()).isFalse();
        assertThat(status.edition()).isEqualTo(Edition.COMMUNITY);
        assertThat(status.customer()).isNull();
        assertThat(status.expiresAt()).isNull();
    }

    @Test
    void validEnterpriseToken_isEnterprise() {
        LicenseService service = service(Optional.of("token"), Optional.of(enterprise()), now);

        assertThat(service.currentEdition()).isEqualTo(Edition.ENTERPRISE);
        LicenseStatus status = service.status();
        assertThat(status.licensed()).isTrue();
        assertThat(status.edition()).isEqualTo(Edition.ENTERPRISE);
        assertThat(status.customer()).isEqualTo("Acme Ltd");
        assertThat(status.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void expiredEnterpriseToken_fallsBackToCommunityButStillReportsTheLicence() {
        LicenseService service = service(Optional.of("token"), Optional.of(enterprise()),
            Clock.fixed(AFTER_EXPIRY, ZoneOffset.UTC));

        assertThat(service.currentEdition()).isEqualTo(Edition.COMMUNITY);
        LicenseStatus status = service.status();
        assertThat(status.licensed()).isFalse();
        assertThat(status.edition()).isEqualTo(Edition.COMMUNITY);
        assertThat(status.customer()).isEqualTo("Acme Ltd");
        assertThat(status.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    void unverifiableToken_isCommunity() {
        LicenseService service = service(Optional.of("garbage"), Optional.empty(), now);

        assertThat(service.currentEdition()).isEqualTo(Edition.COMMUNITY);
        assertThat(service.status().licensed()).isFalse();
    }
}
