package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;

/**
 * Startup bootstrap for Vaier's own infrastructure. Ensures the mandatory infra DNS records exist:
 * the {@code vaier.<domain>} console record pointing at this server, plus the {@code oauth2.<domain>}
 * and {@code dex.<domain>} auth-stack CNAMEs the sign-in stack needs to come up. Each is created only
 * when missing — existing records are never disturbed.
 */
@Slf4j
public class Lifecycle {

    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForResolvingPublicHost publicHostResolver;
    private final String vaierDomain;

    public Lifecycle(
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForResolvingPublicHost publicHostResolver,
        String vaierDomain
    ) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.publicHostResolver = publicHostResolver;
        this.vaierDomain = vaierDomain;
    }

    public void start() {
        initDns();
    }

    private void initDns() {
        if(vaierDomain == null || vaierDomain.isBlank()) {
            throw new RuntimeException("VAIER_DOMAIN is not set");
        }
        DnsZone dnsZone = forPersistingDnsRecords.getDnsZones().stream()
            .filter(zone -> zone.name().equals(vaierDomain))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("DNS zone not found for " + vaierDomain));

        log.info("DNS zone found: " + dnsZone.name());

        VaierHostnames hostnames = new VaierHostnames(vaierDomain);
        List<DnsRecord> records = forPersistingDnsRecords.getDnsRecords(dnsZone);

        // The vaier console record targets this server's resolved public address (A or CNAME).
        String vaierHost = hostnames.vaierServerFqdn();
        if (recordExists(records, vaierHost)) {
            log.info("DNS record found: " + vaierHost);
        } else {
            ensureVaierRecord(vaierHost, dnsZone);
        }

        // oauth2-proxy and Dex are CNAMEs to the vaier host — a domain-owned decision. Their target is
        // static, so they are ensured whether or not the public address could be resolved above.
        for (DnsRecord infraRecord : hostnames.authInfrastructureCnames()) {
            if (recordExists(records, infraRecord.name())) {
                log.info("DNS record found: " + infraRecord.name());
            } else {
                forPersistingDnsRecords.addDnsRecord(infraRecord, dnsZone);
                log.info("Added {} {} record → {}", infraRecord.name(), infraRecord.type(), infraRecord.values());
            }
        }
    }

    private boolean recordExists(List<DnsRecord> records, String name) {
        return records.stream().anyMatch(record -> record.name().equals(name));
    }

    private boolean ensureVaierRecord(String vaierHost, DnsZone dnsZone) {
        Optional<PublicHost> resolved = publicHostResolver.resolve();
        if (resolved.isEmpty()) {
            log.warn("==========================================================");
            log.warn("DNS record missing for {} and this server's public address", vaierHost);
            log.warn("could not be determined automatically.");
            log.warn("Create the record manually in Route53, or set");
            log.warn("VAIER_PUBLIC_HOST (CNAME target) or VAIER_PUBLIC_IP (A target)");
            log.warn("in .env and restart the stack.");
            log.warn("==========================================================");
            return false;
        }
        PublicHost publicHost = resolved.get();
        forPersistingDnsRecords.addDnsRecord(
            new DnsRecord(vaierHost, publicHost.type(), 300L, List.of(publicHost.value())),
            dnsZone
        );
        log.info("Added {} {} record → {}", vaierHost, publicHost.type(), publicHost.value());
        return true;
    }
}
