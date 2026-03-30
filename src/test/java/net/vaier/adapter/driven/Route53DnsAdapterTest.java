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
