package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForResolvingDns;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class DnsJavaAuthoritativeResolverAdapter implements ForResolvingDns {

    @FunctionalInterface
    interface NameserverDiscovery {
        List<String> discover(String zone);
    }

    @FunctionalInterface
    interface RecordQueryAtNameserver {
        boolean hasRecord(String fqdn, String nsHost);
    }

    private final ConfigResolver configResolver;

    NameserverDiscovery nameserverDiscovery = new DnsJavaNameserverDiscovery();
    RecordQueryAtNameserver recordQueryAtNameserver = new DnsJavaRecordQueryAtNameserver();

    public DnsJavaAuthoritativeResolverAdapter(ConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    @Override
    public boolean isResolvable(String fqdn) {
        String zone = configResolver.getDomain();
        if (zone == null || zone.isBlank()) {
            log.warn("No zone configured, cannot resolve {}", fqdn);
            return false;
        }
        List<String> nameservers = nameserverDiscovery.discover(zone);
        if (nameservers.isEmpty()) {
            log.warn("No authoritative nameservers found for zone {}", zone);
            return false;
        }
        for (String ns : nameservers) {
            if (recordQueryAtNameserver.hasRecord(fqdn, ns)) {
                log.info("{} resolved at authoritative NS {}", fqdn, ns);
                return true;
            }
        }
        return false;
    }

    static class DnsJavaNameserverDiscovery implements NameserverDiscovery {
        @Override
        public List<String> discover(String zone) {
            try {
                Lookup lookup = new Lookup(Name.fromString(zone, Name.root), Type.NS);
                lookup.setCache(new Cache());
                Record[] records = lookup.run();
                if (records == null) {
                    log.warn("NS lookup for zone {} returned null (result={}, error={})",
                        zone, lookup.getResult(), lookup.getErrorString());
                    return Collections.emptyList();
                }
                List<String> hosts = new ArrayList<>();
                for (Record r : records) {
                    if (r instanceof NSRecord ns) {
                        hosts.add(ns.getTarget().toString(true));
                    }
                }
                log.debug("Discovered {} authoritative nameservers for zone {}: {}", hosts.size(), zone, hosts);
                return hosts;
            } catch (Exception e) {
                log.warn("Failed to discover nameservers for {}: {}", zone, e.getMessage());
                return Collections.emptyList();
            }
        }
    }

    static class DnsJavaRecordQueryAtNameserver implements RecordQueryAtNameserver {
        @Override
        public boolean hasRecord(String fqdn, String nsHost) {
            try {
                SimpleResolver resolver = new SimpleResolver(nsHost);
                resolver.setTimeout(Duration.ofSeconds(5));
                Name name = Name.fromString(fqdn, Name.root);
                // Query CNAME explicitly — the service creates CNAME records, and asking for Type.A
                // forces dnsjava to follow the CNAME target through a different resolver, which
                // often fails silently when run against a single authoritative NS.
                Lookup lookup = new Lookup(name, Type.CNAME);
                lookup.setResolver(resolver);
                lookup.setCache(new Cache());
                Record[] records = lookup.run();
                if (records != null && records.length > 0) return true;
                log.debug("NS {} has no CNAME for {} yet (result={}, error={})",
                    nsHost, fqdn, lookup.getResult(), lookup.getErrorString());
                return false;
            } catch (Exception e) {
                log.debug("Failed to query {} at NS {}: {}", fqdn, nsHost, e.getMessage());
                return false;
            }
        }
    }
}
