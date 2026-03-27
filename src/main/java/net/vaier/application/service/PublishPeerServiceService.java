package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublishPeerServiceService implements PublishPeerServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;

    @Value("${VAIER_DOMAIN:}")
    private String vaierDomain;

    @Override
    public void publishService(String peerIp, int port, String subdomain, boolean requiresAuth) {
        String fqdn = subdomain + "." + vaierDomain;
        String serverFqdn = "vaier." + vaierDomain;

        log.info("Publishing service: {} -> {}:{} (auth: {})", fqdn, peerIp, port, requiresAuth);

        forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, peerIp, port, requiresAuth);
        log.info("Created Traefik route for {}", fqdn);

        DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        DnsZone zone = new DnsZone(vaierDomain);
        forPersistingDnsRecords.addDnsRecord(cname, zone);
        log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);
    }
}
