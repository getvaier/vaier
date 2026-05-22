package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;

import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves the Vaier server's public-facing host — the IP to geolocate and the label to show.
 * Owns the fallback precedence and the A-vs-CNAME branching; the caller supplies the port and a
 * DNS resolver and performs the geolocation itself.
 */
public final class ServerLocationResolver {

    private ServerLocationResolver() {}

    /** A resolved public-facing host: the IP to geolocate and the human-facing display label. */
    public record ResolvedHost(String publicIp, String displayLabel) {}

    /**
     * The four-tier fallback for the public IP: a direct public IP (EC2 IMDS, immune to
     * split-horizon DNS); else the configured public host (an A record's value used as-is, a
     * CNAME's value DNS-resolved); else {@code vaier.<baseDomain>} DNS-resolved. Empty when none
     * yields an IP. The display label prefers the public host's name, then the fallback FQDN,
     * then the IP itself.
     */
    public static Optional<ResolvedHost> resolve(ForResolvingPublicHost publicHostPort,
                                                 Function<String, String> dnsResolver,
                                                 String baseDomain) {
        String displayHost = publicHostPort.resolve().map(PublicHost::value).orElse(null);

        Optional<String> publicIp = publicHostPort.resolvePublicIp();
        if (publicIp.isEmpty()) {
            Optional<PublicHost> host = publicHostPort.resolve();
            if (host.isPresent()) {
                String value = host.get().value();
                String resolved = host.get().type() == DnsRecordType.A ? value : dnsResolver.apply(value);
                if (resolved != null) {
                    publicIp = Optional.of(resolved);
                }
            }
        }
        if (publicIp.isEmpty() && baseDomain != null && !baseDomain.isBlank()) {
            String fallbackHost = new VaierHostnames(baseDomain).vaierServerFqdn();
            String resolved = dnsResolver.apply(fallbackHost);
            if (resolved != null) {
                publicIp = Optional.of(resolved);
                if (displayHost == null) {
                    displayHost = fallbackHost;
                }
            }
        }
        if (publicIp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedHost(publicIp.get(),
            displayHost != null ? displayHost : publicIp.get()));
    }
}
