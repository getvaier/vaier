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
        String hostedZoneId = findHostedZoneId(dnsZone.name());
        if (hostedZoneId == null) {
            throw new RuntimeException("Hosted zone not found: " + dnsZone.name());
        }

        ResourceRecordSet recordSet = toResourceRecordSet(dnsRecord);

        Change change = Change.builder()
                .action(ChangeAction.CREATE)
                .resourceRecordSet(recordSet)
                .build();

        ChangeBatch changeBatch = ChangeBatch.builder()
                .changes(change)
                .build();

        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(changeBatch)
                .build();

        try {
            route53Client.changeResourceRecordSets(request);
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to update DNS record", e);
        }
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
                    DnsRecord dnsRecord = toDomain(recordSet);
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
        String hostedZoneId = findHostedZoneId(dnsZone.name());
        if (hostedZoneId == null) {
            throw new RuntimeException("Hosted zone not found: " + dnsZone.name());
        }

        ResourceRecordSet recordSet = toResourceRecordSet(dnsRecord);

        Change change = Change.builder()
                .action(ChangeAction.UPSERT)
                .resourceRecordSet(recordSet)
                .build();

        ChangeBatch changeBatch = ChangeBatch.builder()
                .changes(change)
                .build();

        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(changeBatch)
                .build();

        try {
            route53Client.changeResourceRecordSets(request);
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to update DNS record", e);
        }
    }

    @Override
    public void deleteDnsRecord(String recordName, DnsRecordType recordType, DnsZone dnsZone) {
        String hostedZoneId = findHostedZoneId(dnsZone.name());
        if (hostedZoneId == null) {
            throw new RuntimeException("Hosted zone not found: " + dnsZone.name());
        }

        // Fetch the existing record to get TTL and values
        DnsRecord existingRecord = getDnsRecords(dnsZone).stream()
                .filter(record -> record.name().equals(recordName) && record.type() == recordType)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("DNS record not found: " + recordName + " (" + recordType + ")"));

        ResourceRecordSet recordSet = toResourceRecordSet(existingRecord);

        Change change = Change.builder()
                .action(ChangeAction.DELETE)
                .resourceRecordSet(recordSet)
                .build();

        ChangeBatch changeBatch = ChangeBatch.builder()
                .changes(change)
                .build();

        ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(changeBatch)
                .build();

        try {
            route53Client.changeResourceRecordSets(request);
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to delete DNS record", e);
        }
    }

    @Override
    public void addDnsZone(DnsZone dnsZone) {
        CreateHostedZoneRequest request = CreateHostedZoneRequest.builder()
                .name(dnsZone.name())
                .callerReference(String.valueOf(System.currentTimeMillis()))
                .build();

        try {
            route53Client.createHostedZone(request);
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to create hosted zone", e);
        }
    }

    @Override
    public List<DnsZone> getDnsZones() {
        return route53Client.listHostedZones().hostedZones().stream()
                .map(zone -> new DnsZone(zone.name()))
                .collect(Collectors.toList());
    }

    @Override
    public void updateDnsZone(DnsZone dnsZone) {
        // Route53 hosted zones don't support direct updates to the zone name
        // This would require deleting and recreating the zone, which is destructive
        throw new UnsupportedOperationException("Updating DNS zones is not supported in Route53");
    }

    @Override
    public void deleteDnsZone(DnsZone dnsZone) {
        String hostedZoneId = findHostedZoneId(dnsZone.name());
        if (hostedZoneId == null) {
            throw new RuntimeException("Hosted zone not found: " + dnsZone.name());
        }

        DeleteHostedZoneRequest request = DeleteHostedZoneRequest.builder()
                .id(hostedZoneId)
                .build();

        try {
            route53Client.deleteHostedZone(request);
        } catch (Route53Exception e) {
            throw new RuntimeException("Failed to delete hosted zone", e);
        }
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

    private ResourceRecordSet toResourceRecordSet(DnsRecord dnsRecord) {
        return ResourceRecordSet.builder()
                .name(dnsRecord.name())
                .type(RRType.fromValue(dnsRecord.type().getValue()))
                .ttl(dnsRecord.ttl())
                .resourceRecords(dnsRecord.values().stream()
                        .map(value -> ResourceRecord.builder().value(value).build())
                        .toList())
                .build();
    }

    private DnsRecord toDomain(ResourceRecordSet recordSet) {
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
