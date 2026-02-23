package com.wireweave.application;

import java.util.List;

public interface AddDnsRecordUseCase {

    void addDnsRecord(DnsRecordUco dnsRecord, String zoneName);

    record DnsRecordUco(
        String name,
        String type,
        Long ttl,
        List<String> values
    ) {}
}
