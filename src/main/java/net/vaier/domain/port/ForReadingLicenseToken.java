package net.vaier.domain.port;

import java.util.Optional;

/**
 * Driven port that supplies the raw licence token installed on this instance, if any. The driven
 * side owns where it comes from (the {@code VAIER_LICENSE} environment variable, a mounted file,
 * persisted config) so the application service never reads the environment directly. Empty means
 * no licence is installed — the instance runs as Community.
 */
public interface ForReadingLicenseToken {

    Optional<String> readToken();
}
