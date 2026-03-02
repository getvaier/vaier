package net.vaier.domain;

import java.util.List;
import lombok.Getter;

public record DnsRecord(
        String name,
        DnsRecordType type,
        Long ttl,
        List<String> values
) {

    @Getter
    public enum DnsRecordType {
        SOA("SOA"),
        A("A"),
        TXT("TXT"),
        NS("NS"),
        CNAME("CNAME"),
        MX("MX"),
        NAPTR("NAPTR"),
        PTR("PTR"),
        SRV("SRV"),
        SPF("SPF"),
        AAAA("AAAA"),
        CAA("CAA"),
        DS("DS");

        private final String value;

        DnsRecordType(String value) {
            this.value = value;
        }

    }
}
