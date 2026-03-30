package net.vaier.application.service;

import net.vaier.application.AddDnsRecordUseCase.DnsRecordUco;
import net.vaier.application.AddDnsZoneUseCase.DnsZoneUco;
import net.vaier.application.GetDnsInfoUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DnsServiceTest {

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @InjectMocks
    DnsService service;

    // --- getDnsZones ---

    @Test
    void getDnsZones_returnsMappedUcos() {
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(new DnsZone("example.com"), new DnsZone("other.net")));

        List<GetDnsInfoUseCase.DnsZoneUco> result = service.getDnsZones();

        assertThat(result).extracting(GetDnsInfoUseCase.DnsZoneUco::name)
            .containsExactly("example.com", "other.net");
    }

    @Test
    void getDnsZones_emptyList_returnsEmpty() {
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of());

        assertThat(service.getDnsZones()).isEmpty();
    }

    // --- getDnsRecords ---

    @Test
    void getDnsRecords_filtersToOnlyCnameRecords() {
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            record("a.example.com", DnsRecordType.A),
            record("cname.example.com", DnsRecordType.CNAME),
            record("mx.example.com", DnsRecordType.MX)
        ));

        List<GetDnsInfoUseCase.DnsRecordUco> result = service.getDnsRecords(new GetDnsInfoUseCase.DnsZoneUco("example.com"));

        assertThat(result).extracting(GetDnsInfoUseCase.DnsRecordUco::name)
            .containsExactly("cname.example.com");
    }

    @Test
    void getDnsRecords_noCnameRecords_returnsEmpty() {
        DnsZone zone = new DnsZone("example.com");
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(
            record("ns.example.com", DnsRecordType.NS),
            record("soa.example.com", DnsRecordType.SOA)
        ));

        assertThat(service.getDnsRecords(new GetDnsInfoUseCase.DnsZoneUco("example.com"))).isEmpty();
    }

    @Test
    void getDnsRecords_emptyList_returnsEmpty() {
        when(forPersistingDnsRecords.getDnsRecords(any())).thenReturn(List.of());

        assertThat(service.getDnsRecords(new GetDnsInfoUseCase.DnsZoneUco("example.com"))).isEmpty();
    }

    // --- addDnsRecord ---

    @Test
    void addDnsRecord_mapsUcoToDomainAndCallsPort() {
        DnsRecordUco uco = new DnsRecordUco("sub.example.com", "CNAME", 300L, List.of("target.example.com."));

        service.addDnsRecord(uco, "example.com");

        ArgumentCaptor<DnsRecord> recordCaptor = ArgumentCaptor.forClass(DnsRecord.class);
        ArgumentCaptor<DnsZone> zoneCaptor = ArgumentCaptor.forClass(DnsZone.class);
        verify(forPersistingDnsRecords).addDnsRecord(recordCaptor.capture(), zoneCaptor.capture());

        assertThat(recordCaptor.getValue().name()).isEqualTo("sub.example.com");
        assertThat(recordCaptor.getValue().type()).isEqualTo(DnsRecordType.CNAME);
        assertThat(recordCaptor.getValue().ttl()).isEqualTo(300L);
        assertThat(recordCaptor.getValue().values()).containsExactly("target.example.com.");
        assertThat(zoneCaptor.getValue().name()).isEqualTo("example.com");
    }

    @Test
    void addDnsRecord_invalidType_throwsIllegalArgumentException() {
        DnsRecordUco uco = new DnsRecordUco("sub.example.com", "INVALID_TYPE", 300L, List.of());

        assertThatThrownBy(() -> service.addDnsRecord(uco, "example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- deleteDnsRecord ---

    @Test
    void deleteDnsRecord_mapsTypeAndCallsPort() {
        service.deleteDnsRecord("sub.example.com", "CNAME", "example.com");

        ArgumentCaptor<DnsZone> zoneCaptor = ArgumentCaptor.forClass(DnsZone.class);
        verify(forPersistingDnsRecords).deleteDnsRecord("sub.example.com", DnsRecordType.CNAME, new DnsZone("example.com"));
    }

    @Test
    void deleteDnsRecord_invalidType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.deleteDnsRecord("sub.example.com", "BOGUS", "example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- addDnsZone ---

    @Test
    void addDnsZone_wrapsNameInDnsZoneAndDelegates() {
        service.addDnsZone(new DnsZoneUco("newzone.com"));

        verify(forPersistingDnsRecords).addDnsZone(new DnsZone("newzone.com"));
    }

    // --- deleteDnsZone ---

    @Test
    void deleteDnsZone_wrapsNameInDnsZoneAndDelegates() {
        service.deleteDnsZone("oldzone.com");

        verify(forPersistingDnsRecords).deleteDnsZone(new DnsZone("oldzone.com"));
    }

    // --- helpers ---

    private DnsRecord record(String name, DnsRecordType type) {
        return new DnsRecord(name, type, 300L, List.of());
    }
}
