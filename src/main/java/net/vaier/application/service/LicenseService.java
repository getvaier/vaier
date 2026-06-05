package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetEditionUseCase;
import net.vaier.application.GetLicenseStatusUseCase;
import net.vaier.domain.Edition;
import net.vaier.domain.License;
import net.vaier.domain.port.ForReadingLicenseToken;
import net.vaier.domain.port.ForVerifyingLicense;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves the running {@link Edition} from the installed licence. The token is read once and
 * verified once (the signature check is deterministic and the token does not change at runtime),
 * but the <em>effective</em> edition is recomputed against the clock on every call so an expiry
 * takes effect without a restart. A {@link #reload()} re-reads the token for the case where a
 * licence is installed into a running instance.
 */
@Service
@Slf4j
public class LicenseService implements GetEditionUseCase, GetLicenseStatusUseCase {

    private final ForReadingLicenseToken tokenReader;
    private final ForVerifyingLicense verifier;
    private final Clock clock;
    private volatile Optional<License> license = Optional.empty();

    @Autowired
    public LicenseService(ForReadingLicenseToken tokenReader, ForVerifyingLicense verifier) {
        this(tokenReader, verifier, Clock.systemUTC());
    }

    LicenseService(ForReadingLicenseToken tokenReader, ForVerifyingLicense verifier, Clock clock) {
        this.tokenReader = tokenReader;
        this.verifier = verifier;
        this.clock = clock;
        reload();
    }

    /** Re-reads and re-verifies the installed token. */
    public void reload() {
        this.license = tokenReader.readToken().flatMap(verifier::verify);
        license.ifPresent(l -> log.info("Enterprise licence installed for {} ({}edition, expires {})",
            l.customer(), l.edition().name().toLowerCase() + " ",
            l.expiresAt() == null ? "never" : l.expiresAt()));
    }

    @Override
    public Edition currentEdition() {
        return license.map(l -> l.effectiveEdition(now())).orElse(Edition.COMMUNITY);
    }

    @Override
    public LicenseStatus status() {
        Edition edition = currentEdition();
        return new LicenseStatus(
            edition,
            edition == Edition.ENTERPRISE,
            license.map(License::customer).orElse(null),
            license.map(License::expiresAt).orElse(null));
    }

    private Instant now() {
        return clock.instant();
    }
}
