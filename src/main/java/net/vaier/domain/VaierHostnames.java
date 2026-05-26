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
