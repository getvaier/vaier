package net.vaier.application.service;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.DeleteDnsRecordUseCase;
import net.vaier.application.DeleteDnsZoneUseCase;
import net.vaier.application.GetDnsInfoUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DnsService implements GetDnsInfoUseCase, AddDnsRecordUseCase, AddDnsZoneUseCase, DeleteDnsRecordUseCase, DeleteDnsZoneUseCase {

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

    @Override
    public void deleteDnsRecord(String recordName, String recordType, String zoneName) {
        DnsZone dnsZone = new DnsZone(zoneName);
        DnsRecordType dnsRecordType = DnsRecordType.valueOf(recordType);
        forPersistingDnsRecords.deleteDnsRecord(recordName, dnsRecordType, dnsZone);
    }

    @Override
    public void deleteDnsZone(String zoneName) {
        DnsZone dnsZone = new DnsZone(zoneName);
        forPersistingDnsRecords.deleteDnsZone(dnsZone);
    }
}
