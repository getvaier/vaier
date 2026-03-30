package net.vaier.application.service;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeleteHostedServiceServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForPersistingDnsRecords forPersistingDnsRecords;

    @InjectMocks
    DeleteHostedServiceService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "vaierDomain", "example.com");
    }

    @Test
    void deleteService_deletesTraefikRouteByFqdn() {
        service.deleteService("app.example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
    }

    @Test
    void deleteService_deletesDnsCnameRecordWithCorrectZone() {
        service.deleteService("app.example.com");

        verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone("example.com"));
    }

    @Test
    void deleteService_deletesTraefikBeforeDns() {
        service.deleteService("app.example.com");

        InOrder order = inOrder(forPersistingReverseProxyRoutes, forPersistingDnsRecords);
        order.verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
        order.verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone("example.com"));
    }

    @Test
    void deleteService_emptyDomain_passesDnsZoneWithEmptyString() {
        ReflectionTestUtils.setField(service, "vaierDomain", "");

        service.deleteService("app.example.com");

        verify(forPersistingDnsRecords).deleteDnsRecord("app.example.com", DnsRecordType.CNAME, new DnsZone(""));
    }
}
