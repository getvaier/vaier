package net.vaier.adapter.driven;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualDnsAdapterTest {

    @Mock ConfigResolver configResolver;

    private ManualDnsAdapter adapter;

    @BeforeEach
    void setUp() {
        when(configResolver.getDomain()).thenReturn("example.com");
        adapter = new ManualDnsAdapter(configResolver);
    }

    @Test
    void getDnsZones_returnsSingleZoneForConfiguredDomain() {
        List<DnsZone> zones = adapter.getDnsZones();

        assertThat(zones).containsExactly(new DnsZone("example.com"));
    }

    @Test
    void getDnsRecords_synthesizesBootstrapRecordsForConfiguredZone() {
        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));

        assertThat(records).extracting(DnsRecord::name)
            .containsExactlyInAnyOrder("vaier.example.com", "oauth2.example.com", "dex.example.com");
        assertThat(records).allMatch(r -> r.type() == DnsRecordType.CNAME);
    }

    @Test
    void getDnsRecords_returnsEmptyForUnknownZone() {
        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("other.com"));

        assertThat(records).isEmpty();
    }

    @Test
    void addDnsRecord_isNoOp() {
        adapter.addDnsRecord(
            new DnsRecord("foo.example.com.", DnsRecordType.CNAME, 300L, List.of("vaier.example.com.")),
            new DnsZone("example.com")
        );

        // Synthesized boot records remain; no foo.example.com is persisted
        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));
        assertThat(records).extracting(DnsRecord::name)
            .doesNotContain("foo.example.com");
    }

    @Test
    void deleteDnsRecord_isNoOp() {
        adapter.deleteDnsRecord("vaier.example.com.", DnsRecordType.CNAME, new DnsZone("example.com"));

        // Synthesized records still surface — adapter doesn't actually mutate state
        List<DnsRecord> records = adapter.getDnsRecords(new DnsZone("example.com"));
        assertThat(records).extracting(DnsRecord::name)
            .contains("vaier.example.com");
    }

    @Test
    void updateDnsRecord_isNoOp() {
        adapter.updateDnsRecord(
            new DnsRecord("vaier.example.com", DnsRecordType.A, 300L, List.of("1.2.3.4")),
            new DnsZone("example.com")
        );
        // No throw, no state change — the three mandatory infra records still surface
        assertThat(adapter.getDnsRecords(new DnsZone("example.com"))).hasSize(3);
    }

    @Test
    void zoneOps_areNoOps() {
        adapter.addDnsZone(new DnsZone("other.com"));
        adapter.updateDnsZone(new DnsZone("other.com"));
        adapter.deleteDnsZone(new DnsZone("example.com"));
        // Still returns the configured zone
        assertThat(adapter.getDnsZones()).containsExactly(new DnsZone("example.com"));
    }
}
