package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.application.ForPublishingEvents;
import net.vaier.application.UpdatePublishedServiceDnsUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.DnsRecord;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.DnsZone;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdatePublishedServiceDnsService implements UpdatePublishedServiceDnsUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForPublishingEvents forPublishingEvents;
    private final ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;
    private final ConfigResolver configResolver;

    private long dnsTimeoutMillis = 120_000;
    private long dnsRetryIntervalMillis = 3_000;

    // Injectable for testing; queries 8.8.8.8 directly to bypass Docker's internal DNS cache
    private Predicate<String> dnsChecker = fqdn -> {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://8.8.8.8");
            DirContext ctx = new InitialDirContext(env);
            var attrs = ctx.getAttributes(fqdn, new String[]{"CNAME", "A"});
            boolean found = attrs.getAll().hasMore();
            ctx.close();
            return found;
        } catch (NamingException e) {
            return false;
        } catch (Exception e) {
            log.debug("DNS check error for {}: {}", fqdn, e.getMessage());
            return false;
        }
    };

    @Override
    public void updateDns(String currentFqdn, String newSubdomain) {
        String domain = configResolver.getDomain();
        // Preserve any group prefix between the first label and the domain.
        // e.g. "openhab.colina27.example.com" with domain "example.com" → group is "colina27"
        String currentWithoutDomain = currentFqdn.substring(0, currentFqdn.length() - domain.length() - 1);
        int dotIndex = currentWithoutDomain.indexOf('.');
        String newFqdn = dotIndex >= 0
            ? newSubdomain + "." + currentWithoutDomain.substring(dotIndex + 1) + "." + domain
            : newSubdomain + "." + domain;

        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();

        boolean isMandatory = DeletePublishedServiceUseCase.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> currentFqdn.equals(sub + "." + domain));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot change DNS for built-in service: " + currentFqdn);
        }

        ReverseProxyRoute existingRoute = routes.stream()
            .filter(r -> r.getDomainName().equals(currentFqdn))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Route not found: " + currentFqdn));

        boolean newSubdomainInUse = routes.stream()
            .anyMatch(r -> r.getDomainName().equals(newFqdn));
        if (newSubdomainInUse) {
            throw new IllegalArgumentException("Subdomain already in use: " + newSubdomain);
        }

        log.info("Changing DNS for {} to {}", currentFqdn, newFqdn);

        String serverFqdn = "vaier." + domain;
        DnsRecord cname = new DnsRecord(newFqdn + ".", DnsRecordType.CNAME, 300L, List.of(serverFqdn + "."));
        forPersistingDnsRecords.addDnsRecord(cname, new DnsZone(domain));
        log.info("Created new DNS CNAME {} -> {}", newFqdn, serverFqdn);

        CompletableFuture.runAsync(() -> waitForDnsThenSwitch(existingRoute, currentFqdn, newFqdn, domain));
    }

    void waitForDnsThenSwitch(ReverseProxyRoute existingRoute, String oldFqdn, String newFqdn, String domain) {
        long deadline = System.currentTimeMillis() + dnsTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (dnsChecker.test(newFqdn)) {
                log.info("DNS propagated for {}, replacing Traefik route", newFqdn);

                boolean requiresAuth = existingRoute.getMiddlewares() != null &&
                    existingRoute.getMiddlewares().contains("auth-middleware");
                forPersistingReverseProxyRoutes.deleteReverseProxyRoute(existingRoute.getName());
                forPersistingReverseProxyRoutes.addReverseProxyRoute(
                    newFqdn, existingRoute.getAddress(), existingRoute.getPort(),
                    requiresAuth, existingRoute.getRootRedirectPath()
                );
                log.info("Replaced Traefik route from {} to {}", oldFqdn, newFqdn);

                forPersistingDnsRecords.deleteDnsRecord(oldFqdn, DnsRecordType.CNAME, new DnsZone(domain));
                log.info("Deleted old DNS CNAME {}", oldFqdn);

                forInvalidatingPublishedServicesCache.invalidatePublishedServicesCache();
                forPublishingEvents.publish("published-services", "service-updated", newFqdn);
                return;
            }
            log.debug("DNS not yet live for {}, retrying in {}s", newFqdn, dnsRetryIntervalMillis / 1000);
            try { Thread.sleep(dnsRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("DNS propagation timed out for {} — Traefik route NOT updated", newFqdn);
        forPublishingEvents.publish("published-services", "service-updated", oldFqdn);
    }
}
