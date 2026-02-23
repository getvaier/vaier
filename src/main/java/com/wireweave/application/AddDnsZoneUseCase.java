package com.wireweave.application;

public interface AddDnsZoneUseCase {

    void addDnsZone(DnsZoneUco dnsZone);

    record DnsZoneUco(
        String name
    ) {}
}
