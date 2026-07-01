package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;

/**
 * Startup bootstrap for Vaier's own infrastructure. The only step is ensuring the
 * {@code vaier.<domain>} console DNS record exists, pointing at this server.
 */
@Slf4j
public class Lifecycle {

    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForResolvingPublicHost publicHostResolver;
    private final String vaierDomain;
    private final String vaierSubdomain;

    public Lifecycle(
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForResolvingPublicHost publicHostResolver,
        String vaierDomain,
        String vaierSubdomain
    ) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.publicHostResolver = publicHostResolver;
        this.vaierDomain = vaierDomain;
        this.vaierSubdomain = vaierSubdomain;
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

        String vaierHost = vaierSubdomain + "." + vaierDomain;

        List<DnsRecord> records = forPersistingDnsRecords.getDnsRecords(dnsZone);

        Optional<DnsRecord> vaierRecord = records.stream()
            .filter(record -> record.name().equals(vaierHost))
            .findFirst();

        if (vaierRecord.isPresent()) {
            log.info("DNS record found: " + vaierRecord.get().name());
        } else {
            ensureVaierRecord(vaierHost, dnsZone);
        }
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
