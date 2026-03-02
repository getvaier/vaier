package net.vaier.application;

import java.util.List;

public interface GetDnsInfoUseCase {

    List<DnsZoneUco> getDnsZones();
    List<DnsRecordUco> getDnsRecords(DnsZoneUco dnsZone);

    record DnsZoneUco(
        String name
    ) {}

    record DnsRecordUco(
        String name
    ) {}
}
