package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.PublishingConstants;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeletePublishedServiceService implements DeletePublishedServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ConfigResolver configResolver;

    @Override
    public void deleteService(String fqdn) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> fqdn.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot delete built-in service: " + fqdn);
        }
        log.info("Deleting service: {}", fqdn);

        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
        log.info("Deleted Traefik route for {}", fqdn);

        waitForTraefikRouteDeletion(fqdn);

        forPersistingDnsRecords.deleteDnsRecord(fqdn, DnsRecordType.CNAME, new DnsZone(configResolver.getDomain()));
        log.info("Deleted DNS CNAME for {}", fqdn);
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
    }

    private void waitForTraefikRouteDeletion(String fqdn) {
        long deadline = System.currentTimeMillis() + 15_000;
        int consecutiveAbsent = 0;
        while (System.currentTimeMillis() < deadline) {
            boolean stillPresent = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .anyMatch(r -> r.getDomainName().equals(fqdn));
            if (!stillPresent) {
                consecutiveAbsent++;
                if (consecutiveAbsent >= 2) {
                    log.info("Traefik confirmed route deletion for {}", fqdn);
                    return;
                }
            } else {
                consecutiveAbsent = 0;
            }
            log.debug("Waiting for Traefik to remove route for {}", fqdn);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("Traefik did not remove route for {} within 15s, proceeding anyway", fqdn);
    }
}
