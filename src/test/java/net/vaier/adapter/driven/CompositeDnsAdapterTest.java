package net.vaier.adapter.driven;

import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeDnsAdapterTest {

    @Mock ForPersistingDnsRecords route53;
    @Mock ForPersistingDnsRecords cloudflare;

    CompositeDnsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CompositeDnsAdapter(List.of(route53, cloudflare));
    }

    @Test
    void getDnsZones_unionsZonesFromAllProviders() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("old.com")));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("new.com")));

        List<DnsZone> zones = adapter.getDnsZones();

        assertThat(zones).containsExactlyInAnyOrder(new DnsZone("old.com"), new DnsZone("new.com"));
    }

    @Test
    void getDnsZones_dedupesZonesPresentInMultipleProviders() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("shared.com")));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("shared.com")));

        List<DnsZone> zones = adapter.getDnsZones();

        assertThat(zones).containsExactly(new DnsZone("shared.com"));
    }

    @Test
    void addDnsRecord_dispatchesToProviderThatOwnsTheZone() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("old.com")));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("new.com")));
        DnsRecord record = new DnsRecord("api.new.com", DnsRecordType.CNAME, 300L, List.of("target.new.com"));
        DnsZone zone = new DnsZone("new.com");

        adapter.addDnsRecord(record, zone);

        verify(cloudflare).addDnsRecord(record, zone);
        verify(route53, never()).addDnsRecord(any(), any());
    }

    @Test
    void addDnsRecord_throwsWhenNoProviderOwnsTheZone() {
        when(route53.getDnsZones()).thenReturn(List.of());
        when(cloudflare.getDnsZones()).thenReturn(List.of());

        assertThatThrownBy(() -> adapter.addDnsRecord(
                new DnsRecord("x.com", DnsRecordType.A, 300L, List.of("1.1.1.1")),
                new DnsZone("unknown.com")
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No DNS provider owns zone")
                .hasMessageContaining("unknown.com");
    }

    @Test
    void getDnsRecords_delegatesToOwnerProvider() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("old.com")));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("new.com")));
        DnsRecord rec = new DnsRecord("api.new.com", DnsRecordType.A, 300L, List.of("1.2.3.4"));
        DnsZone zone = new DnsZone("new.com");
        when(cloudflare.getDnsRecords(zone)).thenReturn(List.of(rec));

        List<DnsRecord> result = adapter.getDnsRecords(zone);

        assertThat(result).containsExactly(rec);
        verify(route53, never()).getDnsRecords(any());
    }

    @Test
    void deleteDnsRecord_delegatesToOwnerProvider() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("old.com")));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("new.com")));
        DnsZone zone = new DnsZone("new.com");

        adapter.deleteDnsRecord("api.new.com", DnsRecordType.CNAME, zone);

        verify(cloudflare).deleteDnsRecord("api.new.com", DnsRecordType.CNAME, zone);
        verify(route53, never()).deleteDnsRecord(any(), any(), any());
    }

    @Test
    void deleteDnsZone_delegatesToOwnerProvider() {
        when(route53.getDnsZones()).thenReturn(List.of(new DnsZone("old.com")));
        DnsZone zone = new DnsZone("old.com");

        adapter.deleteDnsZone(zone);

        verify(route53).deleteDnsZone(zone);
        verify(cloudflare, never()).deleteDnsZone(any());
    }

    @Test
    void addDnsZone_delegatesToFirstProviderThatAccepts() {
        // Order matters: cloudflare is tried first and rejects; route53 then accepts
        CompositeDnsAdapter ordered = new CompositeDnsAdapter(List.of(cloudflare, route53));
        doThrow(new UnsupportedOperationException()).when(cloudflare).addDnsZone(any());
        DnsZone zone = new DnsZone("newdomain.com");

        ordered.addDnsZone(zone);

        verify(route53).addDnsZone(zone);
    }

    @Test
    void getDnsZones_skipsProviderThatThrowsDuringZoneLookup() {
        when(route53.getDnsZones()).thenThrow(new RuntimeException("AWS creds missing"));
        when(cloudflare.getDnsZones()).thenReturn(List.of(new DnsZone("new.com")));

        List<DnsZone> zones = adapter.getDnsZones();

        assertThat(zones).containsExactly(new DnsZone("new.com"));
    }
}
