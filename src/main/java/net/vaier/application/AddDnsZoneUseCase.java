package net.vaier.application;

public interface AddDnsZoneUseCase {

    void addDnsZone(DnsZoneUco dnsZone);

    record DnsZoneUco(
        String name
    ) {}
}
