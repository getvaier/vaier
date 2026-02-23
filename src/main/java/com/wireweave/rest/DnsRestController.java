package com.wireweave.rest;

import com.wireweave.application.AddDnsRecordUseCase;
import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.application.GetDnsInfoUseCase.DnsRecordUco;
import com.wireweave.application.GetDnsInfoUseCase.DnsZoneUco;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dns")
public class DnsRestController {

    private final GetDnsInfoUseCase getDnsInfoUseCase;
    private final AddDnsRecordUseCase addDnsRecordUseCase;

    public DnsRestController(GetDnsInfoUseCase getDnsInfoUseCase, AddDnsRecordUseCase addDnsRecordUseCase) {
        this.getDnsInfoUseCase = getDnsInfoUseCase;
        this.addDnsRecordUseCase = addDnsRecordUseCase;
    }

    @GetMapping("/zones")
    public List<String> getDnsZones() {
        return getDnsInfoUseCase.getDnsZones().stream().map(DnsZoneUco::name).toList();
    }

    @GetMapping("/zones/{zoneName}/records")
    public List<String> getDnsRecords(@PathVariable String zoneName) {
        return getDnsInfoUseCase.getDnsRecords(new DnsZoneUco(zoneName)).stream().map(DnsRecordUco::name).toList();
    }

    @PostMapping("/zones/{zoneName}/records")
    public void addDnsRecord(@PathVariable String zoneName, @RequestBody AddDnsRecordRequest request) {
        AddDnsRecordUseCase.DnsRecordUco dnsRecord = new AddDnsRecordUseCase.DnsRecordUco(
            request.name(),
            request.type(),
            request.ttl(),
            request.values()
        );
        addDnsRecordUseCase.addDnsRecord(dnsRecord, zoneName);
    }

    public record AddDnsRecordRequest(
        String name,
        String type,
        Long ttl,
        List<String> values
    ) {}
}
