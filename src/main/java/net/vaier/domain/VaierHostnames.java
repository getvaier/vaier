package net.vaier.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.vaier.config.ServiceNames;

/**
 * The Vaier server's own public hostnames, derived from the base domain. Replaces the
 * {@code "vaier." + domain} string concatenation that was duplicated across the application and
 * adapter layers — the subdomain labels are the single {@link ServiceNames} definitions.
 *
 * <p>All three are covered by the operator's single {@code *.<baseDomain>} A record, so Vaier only
 * ever names them — it never creates them (#331).
 */
public record VaierHostnames(String baseDomain) {

    /** The FQDN the Vaier web UI is served on, e.g. {@code vaier.example.com}. */
    public String vaierServerFqdn() {
        return ServiceNames.VAIER + "." + baseDomain;
    }

    /**
     * The same FQDN, or empty when there is no base domain to build it from. A deployment that has not
     * been configured yet genuinely has no name to give out — {@code "vaier.null"} is not a host — and
     * that judgement belongs here, with the type that makes the name, rather than at each caller.
     */
    public Optional<String> configuredVaierServerFqdn() {
        return baseDomain == null || baseDomain.isBlank()
            ? Optional.empty()
            : Optional.of(vaierServerFqdn());
    }

    /** The FQDN oauth2-proxy is served on for social login, e.g. {@code oauth2.example.com}. */
    public String oauth2Host() {
        return ServiceNames.OAUTH2 + "." + baseDomain;
    }

    /** The FQDN the Dex OIDC broker is served on, e.g. {@code dex.example.com}. */
    public String dexHost() {
        return ServiceNames.DEX + "." + baseDomain;
    }

    /** The redirect URI every identity provider's app is registered with: providers hand back to Dex. */
    public String dexCallbackUrl() {
        return "https://" + dexHost() + "/callback";
    }

    /**
     * The URL that logs a social-login session out: oauth2-proxy's {@code /oauth2/sign_out}, which
     * clears the domain-wide SSO cookie, then redirects back to {@code redirectTarget}. The
     * redirect target must fall under {@code .baseDomain} (oauth2-proxy's whitelist-domain).
     */
    public String oauth2SignOutUrl(String redirectTarget) {
        return "https://" + oauth2Host() + "/oauth2/sign_out?rd="
            + URLEncoder.encode(redirectTarget, StandardCharsets.UTF_8);
    }
}
