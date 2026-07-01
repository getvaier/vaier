package net.vaier.domain;

import net.vaier.config.ServiceNames;
import net.vaier.domain.DnsRecord.DnsRecordType;

import java.util.List;

/**
 * The Vaier server's own public hostnames, derived from the base domain. Replaces the
 * {@code "vaier." + domain} string concatenation that was duplicated across the application and
 * adapter layers — the subdomain labels are the single {@link ServiceNames} definitions.
 */
public record VaierHostnames(String baseDomain) {

    /** TTL on the mandatory infrastructure records — short enough that a vaier-host change propagates quickly. */
    private static final long MANDATORY_RECORD_TTL_SECONDS = 300L;

    /** The FQDN the Vaier web UI is served on, e.g. {@code vaier.example.com}. */
    public String vaierServerFqdn() {
        return ServiceNames.VAIER + "." + baseDomain;
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

    /**
     * The DNS records Vaier requires to exist for its own infrastructure — the {@code vaier} web UI,
     * CNAME'd to the Vaier server. Adapters in manual-DNS mode (no upstream provider to query) return
     * this set verbatim instead of inventing it themselves.
     */
    public List<DnsRecord> mandatoryDnsRecords() {
        String vaierHost = vaierServerFqdn();
        return List.of(
            new DnsRecord(vaierHost, DnsRecordType.CNAME, MANDATORY_RECORD_TTL_SECONDS, List.of(vaierHost))
        );
    }
}
