package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@Primary
@Slf4j
public class CompositeDnsAdapter implements ForPersistingDnsRecords {

    private final List<ForPersistingDnsRecords> providers;

    @Autowired
    public CompositeDnsAdapter(Route53DnsAdapter route53, CloudflareDnsAdapter cloudflare) {
        this(List.of(route53, cloudflare));
    }

    CompositeDnsAdapter(List<ForPersistingDnsRecords> providers) {
        this.providers = providers;
    }

    @Override
    public List<DnsZone> getDnsZones() {
        Set<DnsZone> zones = new LinkedHashSet<>();
        for (ForPersistingDnsRecords provider : providers) {
            try {
                zones.addAll(provider.getDnsZones());
            } catch (RuntimeException e) {
                log.warn("DNS provider {} failed to list zones: {}", provider.getClass().getSimpleName(), e.getMessage());
            }
        }
        return List.copyOf(zones);
    }

    @Override
    public List<DnsRecord> getDnsRecords(DnsZone dnsZone) {
        return owner(dnsZone).getDnsRecords(dnsZone);
    }

    @Override
    public void addDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        owner(dnsZone).addDnsRecord(dnsRecord, dnsZone);
    }

    @Override
    public void updateDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {
        owner(dnsZone).updateDnsRecord(dnsRecord, dnsZone);
    }

    @Override
    public void deleteDnsRecord(String recordName, DnsRecordType recordType, DnsZone dnsZone) {
        owner(dnsZone).deleteDnsRecord(recordName, recordType, dnsZone);
    }

    @Override
    public void addDnsZone(DnsZone dnsZone) {
        RuntimeException lastError = null;
        for (ForPersistingDnsRecords provider : providers) {
            try {
                provider.addDnsZone(dnsZone);
                return;
            } catch (UnsupportedOperationException e) {
                lastError = e;
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError
                : new RuntimeException("No DNS provider accepted addDnsZone for " + dnsZone.name());
    }

    @Override
    public void updateDnsZone(DnsZone dnsZone) {
        owner(dnsZone).updateDnsZone(dnsZone);
    }

    @Override
    public void deleteDnsZone(DnsZone dnsZone) {
        owner(dnsZone).deleteDnsZone(dnsZone);
    }

    private ForPersistingDnsRecords owner(DnsZone dnsZone) {
        for (ForPersistingDnsRecords provider : providers) {
            try {
                if (provider.getDnsZones().contains(dnsZone)) {
                    return provider;
                }
            } catch (RuntimeException e) {
                log.warn("DNS provider {} failed to list zones while searching for owner of {}: {}",
                        provider.getClass().getSimpleName(), dnsZone.name(), e.getMessage());
            }
        }
        throw new RuntimeException("No DNS provider owns zone: " + dnsZone.name());
    }
}
