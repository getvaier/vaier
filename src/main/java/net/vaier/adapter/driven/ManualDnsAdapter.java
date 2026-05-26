package net.vaier.adapter.driven;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.port.ForPersistingDnsRecords;

// Manual-DNS mode: there is no upstream provider to read, so the "current state" of the zone is
// just whatever Vaier requires for its own infrastructure. That mandatory set is a domain rule
// owned by VaierHostnames — the adapter doesn't invent records.

@Slf4j
public class ManualDnsAdapter implements ForPersistingDnsRecords {

    private final ConfigResolver configResolver;

    public ManualDnsAdapter(ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    @Override
    public List<DnsZone> getDnsZones() {
        String domain = configResolver.getDomain();
        if (domain == null || domain.isBlank()) return List.of();
        return List.of(new DnsZone(domain));
    }

    @Override
    public List<DnsRecord> getDnsRecords(DnsZone dnsZone) {
        String domain = configResolver.getDomain();
        if (domain == null || !domain.equals(dnsZone.name())) return List.of();
        return new VaierHostnames(domain).mandatoryDnsRecords();
    }

    @Override
    public void addDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        log.info("Manual DNS mode: please ensure record exists in your DNS provider — {} {} {}",
            dnsRecord.name(), dnsRecord.type(), dnsRecord.values());
    }

    @Override
    public void updateDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        log.info("Manual DNS mode: please update record in your DNS provider — {} {} {}",
            dnsRecord.name(), dnsRecord.type(), dnsRecord.values());
    }

    @Override
    public void deleteDnsRecord(String recordName, DnsRecordType recordType, DnsZone dnsZone) {
        log.info("Manual DNS mode: please remove record from your DNS provider — {} {}",
            recordName, recordType);
    }

    @Override
    public void addDnsZone(DnsZone dnsZone) {
        log.info("Manual DNS mode: zone {} must exist in your DNS provider", dnsZone.name());
    }

    @Override
    public void updateDnsZone(DnsZone dnsZone) {
        log.info("Manual DNS mode: zone {} updates are managed in your DNS provider", dnsZone.name());
    }

    @Override
    public void deleteDnsZone(DnsZone dnsZone) {
        log.info("Manual DNS mode: zone {} deletion is managed in your DNS provider", dnsZone.name());
    }
}
