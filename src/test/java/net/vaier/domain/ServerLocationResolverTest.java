package net.vaier.domain;

import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.ServerLocationResolver.ResolvedHost;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ServerLocationResolverTest {

    private static ForResolvingPublicHost publicHost(Optional<PublicHost> host, Optional<String> publicIp) {
        return new ForResolvingPublicHost() {
            @Override public Optional<PublicHost> resolve() { return host; }
            @Override public Optional<String> resolvePublicIp() { return publicIp; }
        };
    }

    private static final Function<String, String> NO_DNS = name -> null;

    @Test
    void resolve_prefersTheDirectPublicIp() {
        ForResolvingPublicHost port = publicHost(
            Optional.of(new PublicHost("vpn.example.com", DnsRecordType.CNAME)),
            Optional.of("54.93.32.13"));

        assertThat(ServerLocationResolver.resolve(port, NO_DNS, "example.com"))
            .contains(new ResolvedHost("54.93.32.13", "vpn.example.com"));
    }

    @Test
    void resolve_aRecordHost_usesItsValueAsTheIpDirectly() {
        ForResolvingPublicHost port = publicHost(
            Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)), Optional.empty());

        assertThat(ServerLocationResolver.resolve(port, NO_DNS, "example.com"))
            .contains(new ResolvedHost("203.0.113.10", "203.0.113.10"));
    }

    @Test
    void resolve_cnameHost_dnsResolvesItsValue() {
        ForResolvingPublicHost port = publicHost(
            Optional.of(new PublicHost("vpn.example.com", DnsRecordType.CNAME)), Optional.empty());
        Function<String, String> dns = name -> "vpn.example.com".equals(name) ? "198.51.100.7" : null;

        assertThat(ServerLocationResolver.resolve(port, dns, "example.com"))
            .contains(new ResolvedHost("198.51.100.7", "vpn.example.com"));
    }

    @Test
    void resolve_noPublicHost_fallsBackToTheVaierServerFqdn() {
        ForResolvingPublicHost port = publicHost(Optional.empty(), Optional.empty());
        Function<String, String> dns = name -> "vaier.example.com".equals(name) ? "192.0.2.5" : null;

        assertThat(ServerLocationResolver.resolve(port, dns, "example.com"))
            .contains(new ResolvedHost("192.0.2.5", "vaier.example.com"));
    }

    @Test
    void resolve_emptyWhenNothingYieldsAnIp() {
        ForResolvingPublicHost port = publicHost(Optional.empty(), Optional.empty());

        assertThat(ServerLocationResolver.resolve(port, NO_DNS, "example.com")).isEmpty();
    }
}
