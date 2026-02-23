package com.wireweave.adapter.driven;

import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.DnsRecord.DnsRecordType;
import com.wireweave.domain.DnsZone;
import com.wireweave.domain.port.ForPersistingDnsRecords;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.ArrayList;
import java.util.List;

@Component
public class Route53Adapter implements ForPersistingDnsRecords {

    private final Route53Client route53Client;

    public Route53Adapter() {
        String accessKeyId = System.getenv("WIREWEAVE_AWS_KEY");
        String secretAccessKey = System.getenv("WIREWEAVE_AWS_SECRET");
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        route53Client = Route53Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .region(Region.AWS_GLOBAL)
            .build();
    }

    @Override
    public void addDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {

    }

    @Override
    public List<DnsRecord> getDnsRecords(DnsZone dnsZone) {
        try {
            String hostedZoneId = findHostedZoneId(dnsZone.name());
            if (hostedZoneId == null) {
                return List.of();
            }

            List<DnsRecord> dnsRecords = new ArrayList<>();
            String nextRecordName = null;
            String nextRecordType = null;

            do {
                ListResourceRecordSetsRequest.Builder requestBuilder = ListResourceRecordSetsRequest.builder()
                        .hostedZoneId(hostedZoneId);

                if (nextRecordName != null) {
                    requestBuilder.startRecordName(nextRecordName);
                    requestBuilder.startRecordType(nextRecordType);
                }

                ListResourceRecordSetsResponse response = route53Client.listResourceRecordSets(requestBuilder.build());

                for (ResourceRecordSet recordSet : response.resourceRecordSets()) {
                    DnsRecord dnsRecord = mapToDnsRecord(recordSet);
                    dnsRecords.add(dnsRecord);
                }

                if (response.isTruncated()) {
                    nextRecordName = response.nextRecordName();
                    nextRecordType = response.nextRecordTypeAsString();
                } else {
                    nextRecordName = null;
                }

            } while (nextRecordName != null);

            return dnsRecords;

        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to get DNS records from Route53", e);
        }
    }

    @Override
    public void updateDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {

    }

    @Override
    public void deleteDnsRecord(DnsRecord dnsRecord, DnsZone dnsZone) {

    }

    @Override
    public void addDnsZone(DnsZone dnsZone) {

    }

    @Override
    public List<DnsZone> getDnsZones() {
        return route53Client.listHostedZones().hostedZones().stream()
                .map(zone -> new DnsZone(zone.name()))
                .collect(Collectors.toList());
    }

    @Override
    public void updateDnsZone(DnsZone dnsZone) {

    }

    @Override
    public void deleteDnsZone(DnsZone dnsZone) {

    }

    private String findHostedZoneId(String domainName) {
        try {
            ListHostedZonesByNameResponse response = route53Client.listHostedZonesByName(
                    ListHostedZonesByNameRequest.builder()
                            .dnsName(domainName)
                            .maxItems("1")
                            .build()
            );

            if (!response.hostedZones().isEmpty()) {
                HostedZone zone = response.hostedZones().getFirst();
                if (zone.name().equals(domainName) || zone.name().equals(domainName + ".")) {
                    // Strip /hostedzone/ prefix if present
                    String zoneId = zone.id();
                    return zoneId.startsWith("/hostedzone/") ? zoneId.substring(12) : zoneId;
                }
            }
            return null;
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to find hosted zone", e);
        }
    }

    private DnsRecord mapToDnsRecord(ResourceRecordSet recordSet) {
        return new DnsRecord(
            recordSet.name(),
            DnsRecordType.valueOf(recordSet.type().name()),
            recordSet.ttl(),
            recordSet.resourceRecords().stream()
                .map(ResourceRecord::value)
                .toList()
        );
    }

    public static void main(String[] args) {
        Route53Adapter adapter = new Route53Adapter();

        List<DnsZone> dnsZones = adapter.getDnsZones();
        dnsZones.forEach(System.out::println);

        DnsZone dnsZone = dnsZones.getFirst();
        List<DnsRecord> records = adapter.getDnsRecords(dnsZone);
        records.forEach(System.out::println);
    }
}
