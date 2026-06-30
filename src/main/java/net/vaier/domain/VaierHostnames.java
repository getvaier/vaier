package net.vaier.domain;

import net.vaier.config.ServiceNames;
import net.vaier.domain.DnsRecord.DnsRecordType;

import java.util.List;

/**
 * The Vaier server's own public hostnames, derived from the base domain. Replaces the
 * {@code "vaier." + domain} / {@code "login." + domain} string concatenation that was
 * duplicated across the application and adapter layers — the subdomain labels are the single
 * {@link ServiceNames#VAIER} / {@link ServiceNames#AUTH} definitions.
 */
public record VaierHostnames(String baseDomain) {

    /** TTL on the mandatory infrastructure records — short enough that a vaier-host change propagates quickly. */
    private static final long MANDATORY_RECORD_TTL_SECONDS = 300L;

    /** The FQDN the Vaier web UI is served on, e.g. {@code vaier.example.com}. */
    public String vaierServerFqdn() {
        return ServiceNames.VAIER + "." + baseDomain;
    }

    /** The FQDN the Authelia login portal is served on, e.g. {@code login.example.com}. */
    public String autheliaHost() {
        return ServiceNames.AUTH + "." + baseDomain;
    }

    /** The FQDN oauth2-proxy is served on for social login, e.g. {@code oauth2.example.com}. */
    public String oauth2Host() {
        return ServiceNames.OAUTH2 + "." + baseDomain;
    }

    /**
     * The URL that logs a social-login session out: oauth2-proxy's {@code /oauth2/sign_out}, which
     * clears the domain-wide SSO cookie, then redirects back to {@code redirectTarget}. The
     * redirect target must fall under {@code .baseDomain} (oauth2-proxy's whitelist-domain).
     */
    public String oauth2SignOutUrl(String redirectTarget) {
        return "https://" + oauth2Host() + "/oauth2/sign_out?rd="
            + java.net.URLEncoder.encode(redirectTarget, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The URL that logs an Authelia session out via the login portal, redirecting back afterwards. */
    public String autheliaLogoutUrl(String redirectTarget) {
        return "https://" + autheliaHost() + "/logout?rd=" + redirectTarget;
    }

    /**
     * The logout URL appropriate for {@code mode}: oauth2-proxy's sign-out for {@link AuthMode#SOCIAL}
     * (clears the domain-wide cookie), the Authelia portal logout for {@link AuthMode#AUTHELIA}, and
     * {@code null} for {@link AuthMode#NONE} (a public surface has no session to end).
     */
    public String logoutUrl(AuthMode mode, String redirectTarget) {
        return switch (mode) {
            case SOCIAL -> oauth2SignOutUrl(redirectTarget);
            case AUTHELIA -> autheliaLogoutUrl(redirectTarget);
            case NONE -> null;
        };
    }

    /**
     * The DNS records Vaier requires to exist for its own infrastructure — the {@code vaier} web
     * UI and the {@code login} Authelia portal, both CNAME'd to the Vaier server. Adapters in
     * manual-DNS mode (no upstream provider to query) return this set verbatim instead of
     * inventing it themselves.
     */
    public List<DnsRecord> mandatoryDnsRecords() {
        String vaierHost = vaierServerFqdn();
        return List.of(
            new DnsRecord(vaierHost, DnsRecordType.CNAME, MANDATORY_RECORD_TTL_SECONDS, List.of(vaierHost)),
            new DnsRecord(autheliaHost(), DnsRecordType.CNAME, MANDATORY_RECORD_TTL_SECONDS, List.of(vaierHost))
        );
    }
}
