package net.vaier.rest;

import net.vaier.application.AddDnsRecordUseCase;
import net.vaier.application.AddDnsZoneUseCase;
import net.vaier.application.DeleteDnsRecordUseCase;
import net.vaier.application.DeleteDnsZoneUseCase;
import net.vaier.application.GetDnsInfoUseCase;
import net.vaier.application.GetDnsInfoUseCase.DnsRecordUco;
import net.vaier.application.GetDnsInfoUseCase.DnsZoneUco;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AddDnsZoneUseCase addDnsZoneUseCase;
    private final DeleteDnsRecordUseCase deleteDnsRecordUseCase;
    private final DeleteDnsZoneUseCase deleteDnsZoneUseCase;

    public DnsRestController(GetDnsInfoUseCase getDnsInfoUseCase, AddDnsRecordUseCase addDnsRecordUseCase, AddDnsZoneUseCase addDnsZoneUseCase, DeleteDnsRecordUseCase deleteDnsRecordUseCase, DeleteDnsZoneUseCase deleteDnsZoneUseCase) {
        this.getDnsInfoUseCase = getDnsInfoUseCase;
        this.addDnsRecordUseCase = addDnsRecordUseCase;
        this.addDnsZoneUseCase = addDnsZoneUseCase;
        this.deleteDnsRecordUseCase = deleteDnsRecordUseCase;
        this.deleteDnsZoneUseCase = deleteDnsZoneUseCase;
    }

    @GetMapping("/zones")
    public List<String> getDnsZones() {
        return getDnsInfoUseCase.getDnsZones().stream().map(DnsZoneUco::name).toList();
    }

    @PostMapping("/zones")
    public void addDnsZone(@RequestBody AddDnsZoneRequest request) {
        AddDnsZoneUseCase.DnsZoneUco dnsZone = new AddDnsZoneUseCase.DnsZoneUco(request.name());
        addDnsZoneUseCase.addDnsZone(dnsZone);
    }

    @DeleteMapping("/zones/{zoneName}")
    public void deleteDnsZone(@PathVariable String zoneName) {
        deleteDnsZoneUseCase.deleteDnsZone(zoneName);
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

    @DeleteMapping("/zones/{zoneName}/records")
    public void deleteDnsRecord(@PathVariable String zoneName, @RequestBody DeleteDnsRecordRequest request) {
        deleteDnsRecordUseCase.deleteDnsRecord(request.name(), request.type(), zoneName);
    }

    public record AddDnsRecordRequest(
        String name,
        String type,
        Long ttl,
        List<String> values
    ) {}

    public record AddDnsZoneRequest(
        String name
    ) {}

    public record DeleteDnsRecordRequest(
        String name,
        String type
    ) {}
}
