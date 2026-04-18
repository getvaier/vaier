package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.Server;
import net.vaier.domain.port.ForGettingServerInfo;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForResolvingDns;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublishPeerServiceService implements PublishPeerServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForPublishingEvents forPublishingEvents;
    private final PendingPublicationsService pendingPublicationsService;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ConfigResolver configResolver;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForResolvingDns forResolvingDns;

    private long dnsTimeoutMillis = 120_000;
    private long dnsRetryIntervalMillis = 3_000;

    private record PendingState(boolean requiresAuth, boolean dnsPropagated) {}

    private final Map<String, PendingState> pendingPublishes = new ConcurrentHashMap<>();

    @Override
    public void publishService(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath) {
        String fqdn = subdomain + "." + configResolver.getDomain();
        String serverFqdn = "vaier." + configResolver.getDomain();

        log.info("Publishing service: {} -> {}:{} (auth: {})", fqdn, address, port, requiresAuth);

        DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        DnsZone zone = new DnsZone(configResolver.getDomain());
        forPersistingDnsRecords.addDnsRecord(cname, zone);
        log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        pendingPublicationsService.track(address, port);
        forPublishingEvents.publish("published-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() -> waitForDnsThenActivate(subdomain, fqdn, address, port, requiresAuth, rootRedirectPath));
    }

    @Override
    public PublishPeerServiceUseCase.PublishStatus getPublishStatus(String subdomain) {
        String fqdn = subdomain + "." + configResolver.getDomain();
        boolean traefikActive = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .anyMatch(r -> r.getDomainName().equals(fqdn));
        if (traefikActive) {
            pendingPublishes.remove(subdomain);
            return new PublishPeerServiceUseCase.PublishStatus(true, true);
        }
        PendingState state = pendingPublishes.getOrDefault(subdomain, new PendingState(false, false));
        return new PublishPeerServiceUseCase.PublishStatus(state.dnsPropagated(), false);
    }

    @Override
    public List<PendingPublication> getPendingPublications() {
        return pendingPublishes.entrySet().stream()
            .map(e -> new PendingPublication(e.getKey(), e.getValue().requiresAuth(), e.getValue().dnsPropagated()))
            .toList();
    }

    void waitForDnsThenActivate(String subdomain, String fqdn, String address, int port, boolean requiresAuth, String rootRedirectPath) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (forResolvingDns.isResolvable(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("published-services", "publish-dns-propagated", subdomain);
                String persistedAddress = forGettingServerInfo.findContainerNameByIp(Server.local(), address).orElse(address);
                if (!persistedAddress.equals(address)) {
                    log.info("Normalized backend address {} -> {} for {}", address, persistedAddress, fqdn);
                }
                forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, persistedAddress, port, requiresAuth, rootRedirectPath);
                log.info("Created Traefik route for {}", fqdn);
                waitForTraefikRoute(fqdn);
                pendingPublicationsService.untrack(address, port);
                pendingPublishes.remove(subdomain);
                publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
                forPublishingEvents.publish("published-services", "publish-traefik-active", subdomain);
                forPublishingEvents.publish("published-services", "service-updated", subdomain);
                return;
            }
            log.debug("DNS not yet live for {}, retrying in {}s", fqdn, dnsRetryIntervalMillis / 1000);
            try { Thread.sleep(dnsRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("DNS propagation timed out for {} after {}s — Traefik route NOT written to avoid invalid certificate", fqdn, dnsTimeoutMillis / 1000);
        pendingPublicationsService.untrack(address, port);
        pendingPublishes.remove(subdomain);
        forPublishingEvents.publish("published-services", "publish-dns-timeout", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    private void waitForTraefikRoute(String fqdn) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            boolean active = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .anyMatch(r -> r.getDomainName().equals(fqdn));
            if (active) {
                log.info("Traefik picked up route for {}", fqdn);
                return;
            }
            log.debug("Waiting for Traefik to pick up route for {}", fqdn);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("Traefik did not pick up route for {} within 15s, proceeding anyway", fqdn);
    }
}
