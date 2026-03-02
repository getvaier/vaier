package net.vaier.domain.port;

import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import java.util.List;

public interface ForPersistingDnsRecords {
    void addDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone);
    List<DnsRecord> getDnsRecords(DnsZone dnsZone);
    void updateDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone);
    void deleteDnsRecord(String recordName, DnsRecordType recordType, DnsZone dnsZone);
    void addDnsZone(DnsZone dnsZone);
    List<DnsZone> getDnsZones();
    void updateDnsZone(DnsZone dnsZone);
    void deleteDnsZone(DnsZone dnsZone);
}
