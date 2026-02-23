package com.wireweave.application.service;

import com.wireweave.application.AddDnsRecordUseCase;
import com.wireweave.application.AddDnsZoneUseCase;
import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.DnsRecord.DnsRecordType;
import com.wireweave.domain.DnsZone;
import com.wireweave.domain.port.ForPersistingDnsRecords;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DnsService implements GetDnsInfoUseCase, AddDnsRecordUseCase, AddDnsZoneUseCase {

    private final ForPersistingDnsRecords forPersistingDnsRecords;

    public DnsService(ForPersistingDnsRecords forPersistingDnsRecords) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
    }

    @Override
    public List<GetDnsInfoUseCase.DnsZoneUco> getDnsZones() {
        return forPersistingDnsRecords.getDnsZones().stream()
            .map(this::toUco).toList();
    }

    @Override
    public List<GetDnsInfoUseCase.DnsRecordUco> getDnsRecords(GetDnsInfoUseCase.DnsZoneUco dnsZone) {
        return forPersistingDnsRecords.getDnsRecords(toDomain(dnsZone))
            .stream()
            .filter(dnsRecord -> dnsRecord.type() == DnsRecordType.CNAME)
            .map(this::toUco)
            .toList();
    }

    private DnsZone toDomain(GetDnsInfoUseCase.DnsZoneUco dnsZoneUco) {
        return new DnsZone(dnsZoneUco.name());
    }

    private GetDnsInfoUseCase.DnsZoneUco toUco(DnsZone dnsZone) {
        return new GetDnsInfoUseCase.DnsZoneUco(dnsZone.name());
    }

    private GetDnsInfoUseCase.DnsRecordUco toUco(DnsRecord dnsRecord) {
        return new GetDnsInfoUseCase.DnsRecordUco(dnsRecord.name());
    }

    @Override
    public void addDnsRecord(AddDnsRecordUseCase.DnsRecordUco dnsRecord, String zoneName) {
        DnsRecord domainRecord = new DnsRecord(
            dnsRecord.name(),
            DnsRecordType.valueOf(dnsRecord.type()),
            dnsRecord.ttl(),
            dnsRecord.values()
        );
        DnsZone dnsZone = new DnsZone(zoneName);
        forPersistingDnsRecords.addDnsRecord(domainRecord, dnsZone);
    }

    @Override
    public void addDnsZone(AddDnsZoneUseCase.DnsZoneUco dnsZone) {
        DnsZone domainZone = new DnsZone(dnsZone.name());
        forPersistingDnsRecords.addDnsZone(domainZone);
    }
}
