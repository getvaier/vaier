package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LifecycleTest {

    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForResolvingPublicHost publicHostResolver;

    private Lifecycle lifecycle() {
        return new Lifecycle(forPersistingDnsRecords, publicHostResolver, "test.example.com", "vaier");
    }

    @Test
    void initDns_autoCreatesVaierCnameRecord_whenMissingAndResolverReturnsHostname() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-1-2-3-4.compute.amazonaws.com", DnsRecordType.CNAME)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        List<DnsRecord> added = captor.getAllValues();
        assertThat(added).anyMatch(r -> r.name().equals("vaier.test.example.com")
            && r.type() == DnsRecordType.CNAME
            && r.values().equals(List.of("ec2-1-2-3-4.compute.amazonaws.com")));
    }

    @Test
    void initDns_autoCreatesVaierARecord_whenMissingAndResolverReturnsIp() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        assertThat(captor.getAllValues()).anyMatch(r -> r.name().equals("vaier.test.example.com")
            && r.type() == DnsRecordType.A
            && r.values().equals(List.of("203.0.113.10")));
    }

    @Test
    void initDns_isSilent_whenWiredWithManualDnsAdapter() {
        net.vaier.config.ConfigResolver configResolver = mock(net.vaier.config.ConfigResolver.class);
        when(configResolver.getDomain()).thenReturn("test.example.com");
        ForPersistingDnsRecords manualAdapter = spy(
            new net.vaier.adapter.driven.ManualDnsAdapter(configResolver));

        Lifecycle lifecycle = new Lifecycle(manualAdapter, publicHostResolver, "test.example.com", "vaier");

        assertThatCode(lifecycle::start).doesNotThrowAnyException();

        verify(manualAdapter, never()).addDnsRecord(any(), any());
        verify(manualAdapter, never()).updateDnsRecord(any(), any());
        verify(manualAdapter, never()).deleteDnsRecord(any(), any(), any());
        verifyNoInteractions(publicHostResolver);
    }

    @Test
    void initDns_doesNotThrow_whenVaierRecordMissingAndResolverEmpty() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve()).thenReturn(Optional.empty());

        assertThatCode(() -> lifecycle().start()).doesNotThrowAnyException();

        verify(forPersistingDnsRecords, never()).addDnsRecord(any(), any());
    }

    @Test
    void initDns_doesNotCreateLoginCnameRecord_afterAutoCreatingVaierRecord() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-1-2-3-4.compute.amazonaws.com", DnsRecordType.CNAME)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        assertThat(captor.getAllValues()).noneMatch(r -> r.name().equals("login.test.example.com"));
    }

    @Test
    void initDns_doesNotConsultResolver_whenVaierRecordAlreadyExists() {
        DnsZone zone = new DnsZone("test.example.com");
        DnsRecord vaierRecord = new DnsRecord("vaier.test.example.com", DnsRecordType.CNAME, 300L,
            List.of("existing.example.com"));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(vaierRecord));

        lifecycle().start();

        verify(publicHostResolver, never()).resolve();
    }
}
