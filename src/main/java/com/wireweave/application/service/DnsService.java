package com.wireweave.application.service;

import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.DnsRecord.DnsRecordType;
import com.wireweave.domain.DnsZone;
import com.wireweave.domain.port.ForPersistingDnsRecords;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DnsService implements GetDnsInfoUseCase {

    private final ForPersistingDnsRecords forPersistingDnsRecords;

    public DnsService(ForPersistingDnsRecords forPersistingDnsRecords) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
    }

    @Override
    public List<DnsZoneUco> getDnsZones() {
        return forPersistingDnsRecords.getDnsZones().stream()
            .map(this::toUco).toList();
    }

    @Override
    public List<DnsRecordUco> getDnsRecords(DnsZoneUco dnsZone) {
        return forPersistingDnsRecords.getDnsRecords(toDomain(dnsZone))
            .stream()
            .filter(dnsRecord -> dnsRecord.type() == DnsRecordType.CNAME)
            .map(this::toUco)
            .toList();
    }

    private DnsZone toDomain(DnsZoneUco dnsZoneUco) {
        return new DnsZone(dnsZoneUco.name());
    }

    private DnsZoneUco toUco(DnsZone dnsZone) {
        return new DnsZoneUco(dnsZone.name());
    }

    private DnsRecordUco toUco(DnsRecord dnsRecord) {
        return new DnsRecordUco(dnsRecord.name());
    }
}
