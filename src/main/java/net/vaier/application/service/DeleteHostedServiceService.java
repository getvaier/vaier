package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostedServiceUseCase;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteHostedServiceService implements DeleteHostedServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;

    @Value("${VAIER_DOMAIN:}")
    private String vaierDomain;

    @Override
    public void deleteService(String subdomain) {
        String fqdn = subdomain + "." + vaierDomain;

        log.info("Deleting service: {}", fqdn);

        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
        log.info("Deleted Traefik route for {}", fqdn);

        forPersistingDnsRecords.deleteDnsRecord(fqdn, DnsRecordType.CNAME, new DnsZone(vaierDomain));
        log.info("Deleted DNS CNAME for {}", fqdn);
    }
}
