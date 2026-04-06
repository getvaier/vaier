package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ForInvalidatingHostedServicesCache;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublishPeerServiceService implements PublishPeerServiceUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForPublishingEvents forPublishingEvents;
    private final PendingPublicationsTracker pendingPublicationsTracker;
    private final ForInvalidatingHostedServicesCache forInvalidatingHostedServicesCache;

    @Value("${VAIER_DOMAIN:}")
    private String vaierDomain;

    private record PendingState(boolean requiresAuth, boolean dnsPropagated) {}

    // Injectable for testing; defaults to real DNS lookup
    private Predicate<String> dnsChecker = fqdn -> {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(fqdn);
            return addresses != null && addresses.length > 0;
        } catch (Exception e) {
            return false;
        }
    };

    private final Map<String, PendingState> pendingPublishes = new ConcurrentHashMap<>();

    @Override
    public void publishService(String address, int port, String subdomain, boolean requiresAuth, String rootRedirectPath) {
        String fqdn = subdomain + "." + vaierDomain;
        String serverFqdn = "vaier." + vaierDomain;

        log.info("Publishing service: {} -> {}:{} (auth: {})", fqdn, address, port, requiresAuth);

        DnsRecord cname = new DnsRecord(fqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        DnsZone zone = new DnsZone(vaierDomain);
        forPersistingDnsRecords.addDnsRecord(cname, zone);
        log.info("Created DNS CNAME {} -> {}", fqdn, serverFqdn);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth, false));
        pendingPublicationsTracker.track(address, port);
        forPublishingEvents.publish("hosted-services", "publish-dns-created", subdomain);

        CompletableFuture.runAsync(() -> waitForDnsThenActivate(subdomain, fqdn, address, port, requiresAuth, rootRedirectPath));
    }

    @Override
    public PublishPeerServiceUseCase.PublishStatus getPublishStatus(String subdomain) {
        String fqdn = subdomain + "." + vaierDomain;
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
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (dnsChecker.test(fqdn)) {
                log.info("DNS propagated for {}, activating Traefik route", fqdn);
                pendingPublishes.compute(subdomain, (k, v) -> new PendingState(v != null && v.requiresAuth(), true));
                forPublishingEvents.publish("hosted-services", "publish-dns-propagated", subdomain);
                forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, address, port, requiresAuth, rootRedirectPath);
                log.info("Created Traefik route for {}", fqdn);
                waitForTraefikRoute(fqdn);
                pendingPublicationsTracker.untrack(address, port);
                pendingPublishes.remove(subdomain);
                forInvalidatingHostedServicesCache.invalidateHostedServicesCache();
                forPublishingEvents.publish("hosted-services", "publish-traefik-active", subdomain);
                forPublishingEvents.publish("hosted-services", "service-updated", subdomain);
                return;
            }
            log.debug("DNS not yet live for {}, retrying in 3s", fqdn);
            try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("DNS propagation timed out for {}, writing Traefik route anyway", fqdn);
        forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, address, port, requiresAuth, rootRedirectPath);
        waitForTraefikRoute(fqdn);
        pendingPublicationsTracker.untrack(address, port);
        pendingPublishes.remove(subdomain);
        forInvalidatingHostedServicesCache.invalidateHostedServicesCache();
        forPublishingEvents.publish("hosted-services", "publish-traefik-active", subdomain);
        forPublishingEvents.publish("hosted-services", "service-updated", subdomain);
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
