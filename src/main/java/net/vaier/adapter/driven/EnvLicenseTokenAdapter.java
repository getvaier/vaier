package net.vaier.adapter.driven;

import net.vaier.domain.port.ForReadingLicenseToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Supplies the installed licence token from configuration. Bound to the {@code vaier.license}
 * property, which Spring resolves from the {@code VAIER_LICENSE} environment variable (relaxed
 * binding) — the operator drops the minted token there and nothing else is needed. An absent or
 * blank value means no licence is installed and the instance runs as Community.
 */
@Component
public class EnvLicenseTokenAdapter implements ForReadingLicenseToken {

    private final String token;

    public EnvLicenseTokenAdapter(@Value("${vaier.license:}") String token) {
        this.token = token;
    }

    @Override
    public Optional<String> readToken() {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token.trim());
    }
}
