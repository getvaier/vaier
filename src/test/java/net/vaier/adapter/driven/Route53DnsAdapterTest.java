package net.vaier.adapter.driven;

import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Route53DnsAdapterTest {

    @Mock Route53Client route53Client;

    Route53DnsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new Route53DnsAdapter(route53Client);
    }

    // --- getDnsZones ---

    @Test
    void getDnsZones_stripsTrailingDotFromZoneName() {
        ListHostedZonesResponse response = mock(ListHostedZonesResponse.class);
        HostedZone zone = mock(HostedZone.class);
        when(zone.name()).thenReturn("example.com.");
        when(response.hostedZones()).thenReturn(List.of(zone));
        when(route53Client.listHostedZones()).thenReturn(response);

        List<DnsZone> result = adapter.getDnsZones();

        assertThat(result).containsExactly(new DnsZone("example.com"));
    }

    @Test
    void getDnsZones_handlesNameWithNoTrailingDot() {
        ListHostedZonesResponse response = mock(ListHostedZonesResponse.class);
        HostedZone zone = mock(HostedZone.class);
        when(zone.name()).thenReturn("example.com");
        when(response.hostedZones()).thenReturn(List.of(zone));
        when(route53Client.listHostedZones()).thenReturn(response);

        List<DnsZone> result = adapter.getDnsZones();

        assertThat(result).containsExactly(new DnsZone("example.com"));
    }

    @Test
    void getDnsZones_returnsEmptyListWhenNoZones() {
        ListHostedZonesResponse response = mock(ListHostedZonesResponse.class);
        when(response.hostedZones()).thenReturn(List.of());
        when(route53Client.listHostedZones()).thenReturn(response);

        assertThat(adapter.getDnsZones()).isEmpty();
    }

    // --- addDnsRecord strips /hostedzone/ prefix from zone ID ---

    @Test
    void addDnsRecord_stripsHostedZonePrefixFromZoneId() {
        stubZoneLookup("example.com", "/hostedzone/ABCDEF123");
        ChangeResourceRecordSetsResponse changeResponse = mock(ChangeResourceRecordSetsResponse.class);
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenReturn(changeResponse);

        adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                new DnsZone("example.com")
        );

        ArgumentCaptor<ChangeResourceRecordSetsRequest> captor =
                ArgumentCaptor.forClass(ChangeResourceRecordSetsRequest.class);
        verify(route53Client).changeResourceRecordSets(captor.capture());
        assertThat(captor.getValue().hostedZoneId()).isEqualTo("ABCDEF123");
    }

    @Test
    void addDnsRecord_handlesZoneIdAlreadyWithoutPrefix() {
        stubZoneLookup("example.com", "ABCDEF123");
        ChangeResourceRecordSetsResponse changeResponse = mock(ChangeResourceRecordSetsResponse.class);
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenReturn(changeResponse);

        adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.CNAME, 300L, List.of("target.example.com")),
                new DnsZone("example.com")
        );

        ArgumentCaptor<ChangeResourceRecordSetsRequest> captor =
                ArgumentCaptor.forClass(ChangeResourceRecordSetsRequest.class);
        verify(route53Client).changeResourceRecordSets(captor.capture());
        assertThat(captor.getValue().hostedZoneId()).isEqualTo("ABCDEF123");
    }

    @Test
    void addDnsRecord_throwsWhenZoneNotFound() {
        ListHostedZonesByNameResponse zoneResponse = mock(ListHostedZonesByNameResponse.class);
        when(zoneResponse.hostedZones()).thenReturn(List.of());
        when(route53Client.listHostedZonesByName(any(ListHostedZonesByNameRequest.class))).thenReturn(zoneResponse);

        assertThatThrownBy(() ->
                adapter.addDnsRecord(
                        new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                        new DnsZone("missing.com")
                )
        ).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Hosted zone not found");
    }

    // --- getDnsRecords ---

    @Test
    void getDnsRecords_stripsTrailingDotFromRecordName() {
        stubZoneLookup("example.com", "/hostedzone/Z123");

        ResourceRecord rr = ResourceRecord.builder().value("1.2.3.4").build();
        ResourceRecordSet rrSet = ResourceRecordSet.builder()
                .name("sub.example.com.")
                .type(RRType.A)
                .ttl(300L)
                .resourceRecords(rr)
                .build();
        ListResourceRecordSetsResponse recordsResponse = mock(ListResourceRecordSetsResponse.class);
        when(recordsResponse.resourceRecordSets()).thenReturn(List.of(rrSet));
        when(recordsResponse.isTruncated()).thenReturn(false);
        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class)))
                .thenReturn(recordsResponse);

        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().name()).isEqualTo("sub.example.com");
        assertThat(records.getFirst().type()).isEqualTo(DnsRecordType.A);
        assertThat(records.getFirst().values()).containsExactly("1.2.3.4");
    }

    @Test
    void getDnsRecords_returnsEmptyListWhenZoneNotFound() {
        ListHostedZonesByNameResponse zoneResponse = mock(ListHostedZonesByNameResponse.class);
        when(zoneResponse.hostedZones()).thenReturn(List.of());
        when(route53Client.listHostedZonesByName(any(ListHostedZonesByNameRequest.class))).thenReturn(zoneResponse);

        assertThat(adapter.getDnsRecords(new DnsZone("missing.com"))).isEmpty();
    }

    // --- AWS API failure handling ---

    @Test
    void addDnsRecord_wrapsThrottlingExceptionFromRoute53() {
        stubZoneLookup("example.com", "/hostedzone/Z123");
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenThrow(ThrottlingException.builder().message("Rate exceeded").build());

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                new DnsZone("example.com")
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to update DNS record")
                .hasCauseInstanceOf(ThrottlingException.class);
    }

    @Test
    void addDnsRecord_wrapsInvalidInputExceptionFromRoute53() {
        stubZoneLookup("example.com", "/hostedzone/Z123");
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenThrow(InvalidInputException.builder().message("Invalid resource record set").build());

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("not-an-ip")),
                new DnsZone("example.com")
        ))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(InvalidInputException.class);
    }

    @Test
    void getDnsRecords_wrapsThrottlingExceptionFromRoute53() {
        stubZoneLookup("example.com", "/hostedzone/Z123");
        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class)))
                .thenThrow(ThrottlingException.builder().message("Rate exceeded").build());

        assertThatThrownBy(() -> adapter.getDnsRecords(new DnsZone("example.com")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to get DNS records")
                .hasCauseInstanceOf(ThrottlingException.class);
    }

    @Test
    void addDnsRecord_propagatesSdkClientExceptionForNetworkFailures() {
        stubZoneLookup("example.com", "/hostedzone/Z123");
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenThrow(SdkClientException.builder().message("Unable to execute HTTP request: connect timed out").build());

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("sub.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
                new DnsZone("example.com")
        )).isInstanceOf(SdkClientException.class);
    }

    @Test
    void getDnsZones_propagatesThrottlingException() {
        when(route53Client.listHostedZones())
                .thenThrow(ThrottlingException.builder().message("Rate exceeded").build());

        assertThatThrownBy(() -> adapter.getDnsZones())
                .isInstanceOf(ThrottlingException.class);
    }

    @Test
    void getDnsZones_propagatesSdkClientExceptionForNetworkFailures() {
        when(route53Client.listHostedZones())
                .thenThrow(SdkClientException.builder().message("Unable to reach endpoint").build());

        assertThatThrownBy(() -> adapter.getDnsZones())
                .isInstanceOf(SdkClientException.class);
    }

    // --- Paginated responses ---

    @Test
    void getDnsRecords_followsPaginationAcrossMultiplePages() {
        stubZoneLookup("example.com", "/hostedzone/Z123");

        ResourceRecordSet page1Record = ResourceRecordSet.builder()
                .name("a.example.com.")
                .type(RRType.A)
                .ttl(300L)
                .resourceRecords(ResourceRecord.builder().value("1.1.1.1").build())
                .build();
        ListResourceRecordSetsResponse page1 = mock(ListResourceRecordSetsResponse.class);
        when(page1.resourceRecordSets()).thenReturn(List.of(page1Record));
        when(page1.isTruncated()).thenReturn(true);
        when(page1.nextRecordName()).thenReturn("b.example.com.");
        when(page1.nextRecordTypeAsString()).thenReturn("A");

        ResourceRecordSet page2Record = ResourceRecordSet.builder()
                .name("b.example.com.")
                .type(RRType.A)
                .ttl(300L)
                .resourceRecords(ResourceRecord.builder().value("2.2.2.2").build())
                .build();
        ListResourceRecordSetsResponse page2 = mock(ListResourceRecordSetsResponse.class);
        when(page2.resourceRecordSets()).thenReturn(List.of(page2Record));
        when(page2.isTruncated()).thenReturn(false);

        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class)))
                .thenReturn(page1, page2);

        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).hasSize(2);
        assertThat(records).extracting(DnsRecord::name)
                .containsExactly("a.example.com", "b.example.com");
        verify(route53Client, times(2)).listResourceRecordSets(any(ListResourceRecordSetsRequest.class));
    }

    @Test
    void getDnsRecords_passesNextRecordNameAndTypeOnSubsequentRequests() {
        stubZoneLookup("example.com", "/hostedzone/Z123");

        ListResourceRecordSetsResponse page1 = mock(ListResourceRecordSetsResponse.class);
        when(page1.resourceRecordSets()).thenReturn(List.of());
        when(page1.isTruncated()).thenReturn(true);
        when(page1.nextRecordName()).thenReturn("next.example.com.");
        when(page1.nextRecordTypeAsString()).thenReturn("CNAME");

        ListResourceRecordSetsResponse page2 = mock(ListResourceRecordSetsResponse.class);
        when(page2.resourceRecordSets()).thenReturn(List.of());
        when(page2.isTruncated()).thenReturn(false);

        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class)))
                .thenReturn(page1, page2);

        adapter.getDnsRecords(new DnsZone("example.com"));

        ArgumentCaptor<ListResourceRecordSetsRequest> captor =
                ArgumentCaptor.forClass(ListResourceRecordSetsRequest.class);
        verify(route53Client, times(2)).listResourceRecordSets(captor.capture());
        ListResourceRecordSetsRequest second = captor.getAllValues().get(1);
        assertThat(second.startRecordName()).isEqualTo("next.example.com.");
        assertThat(second.startRecordTypeAsString()).isEqualTo("CNAME");
    }

    // --- Multiple resource records in one record set ---

    @Test
    void getDnsRecords_mapsMultipleResourceRecordsIntoSingleDnsRecord() {
        stubZoneLookup("example.com", "/hostedzone/Z123");

        ResourceRecordSet rrSet = ResourceRecordSet.builder()
                .name("ns.example.com.")
                .type(RRType.NS)
                .ttl(172800L)
                .resourceRecords(
                        ResourceRecord.builder().value("ns-1.awsdns-01.com.").build(),
                        ResourceRecord.builder().value("ns-2.awsdns-02.net.").build(),
                        ResourceRecord.builder().value("ns-3.awsdns-03.org.").build(),
                        ResourceRecord.builder().value("ns-4.awsdns-04.co.uk.").build()
                )
                .build();
        ListResourceRecordSetsResponse response = mock(ListResourceRecordSetsResponse.class);
        when(response.resourceRecordSets()).thenReturn(List.of(rrSet));
        when(response.isTruncated()).thenReturn(false);
        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class))).thenReturn(response);

        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().type()).isEqualTo(DnsRecordType.NS);
        assertThat(records.getFirst().values()).containsExactly(
                "ns-1.awsdns-01.com.",
                "ns-2.awsdns-02.net.",
                "ns-3.awsdns-03.org.",
                "ns-4.awsdns-04.co.uk."
        );
    }

    @Test
    void addDnsRecord_sendsAllResourceRecordsInBatch() {
        stubZoneLookup("example.com", "/hostedzone/Z123");
        ChangeResourceRecordSetsResponse changeResponse = mock(ChangeResourceRecordSetsResponse.class);
        when(route53Client.changeResourceRecordSets(any(ChangeResourceRecordSetsRequest.class)))
                .thenReturn(changeResponse);

        adapter.addDnsRecord(
                new DnsRecord("mx.example.com", DnsRecordType.MX, 300L,
                        List.of("10 mail1.example.com", "20 mail2.example.com")),
                new DnsZone("example.com")
        );

        ArgumentCaptor<ChangeResourceRecordSetsRequest> captor =
                ArgumentCaptor.forClass(ChangeResourceRecordSetsRequest.class);
        verify(route53Client).changeResourceRecordSets(captor.capture());
        ResourceRecordSet sent = captor.getValue().changeBatch().changes().getFirst().resourceRecordSet();
        assertThat(sent.resourceRecords()).extracting(ResourceRecord::value)
                .containsExactly("10 mail1.example.com", "20 mail2.example.com");
    }

    // --- Invalid / unsupported DNS record types from AWS ---

    @Test
    void getDnsRecords_throwsWhenAwsReturnsRecordTypeNotInDomainEnum() {
        stubZoneLookup("example.com", "/hostedzone/Z123");

        ResourceRecordSet rrSet = ResourceRecordSet.builder()
                .name("host.example.com.")
                .type(RRType.UNKNOWN_TO_SDK_VERSION)
                .ttl(300L)
                .resourceRecords(ResourceRecord.builder().value("opaque").build())
                .build();
        ListResourceRecordSetsResponse response = mock(ListResourceRecordSetsResponse.class);
        when(response.resourceRecordSets()).thenReturn(List.of(rrSet));
        when(route53Client.listResourceRecordSets(any(ListResourceRecordSetsRequest.class))).thenReturn(response);

        assertThatThrownBy(() -> adapter.getDnsRecords(new DnsZone("example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // helpers

    private void stubZoneLookup(String zoneName, String zoneId) {
        ListHostedZonesByNameResponse zoneResponse = mock(ListHostedZonesByNameResponse.class);
        HostedZone zone = mock(HostedZone.class);
        when(zone.name()).thenReturn(zoneName + ".");
        when(zone.id()).thenReturn(zoneId);
        when(zoneResponse.hostedZones()).thenReturn(List.of(zone));
        when(route53Client.listHostedZonesByName(any(ListHostedZonesByNameRequest.class))).thenReturn(zoneResponse);
    }
}
