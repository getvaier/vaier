package net.vaier.adapter.driven;

import net.vaier.config.ConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DnsJavaAuthoritativeResolverAdapterTest {

    @Mock
    ConfigResolver configResolver;

    DnsJavaAuthoritativeResolverAdapter adapter;

    @BeforeEach
    void setUp() {
        lenient().when(configResolver.getDomain()).thenReturn("example.com");
        adapter = new DnsJavaAuthoritativeResolverAdapter(configResolver);
    }

    @Test
    void isResolvable_anyAuthoritativeNsHasRecord_returnsTrue() {
        adapter.nameserverDiscovery = zone -> List.of("ns1.test", "ns2.test");
        adapter.recordQueryAtNameserver = (fqdn, ns) -> "ns2.test".equals(ns);

        assertThat(adapter.isResolvable("app.example.com")).isTrue();
    }

    @Test
    void isResolvable_noAuthoritativeNsHasRecord_returnsFalse() {
        adapter.nameserverDiscovery = zone -> List.of("ns1.test", "ns2.test");
        adapter.recordQueryAtNameserver = (fqdn, ns) -> false;

        assertThat(adapter.isResolvable("app.example.com")).isFalse();
    }

    @Test
    void isResolvable_noAuthoritativeNameserversFound_returnsFalse() {
        adapter.nameserverDiscovery = zone -> List.of();

        assertThat(adapter.isResolvable("app.example.com")).isFalse();
    }

    @Test
    void isResolvable_noDomainConfigured_returnsFalse() {
        when(configResolver.getDomain()).thenReturn(null);

        assertThat(adapter.isResolvable("app.example.com")).isFalse();
    }

    @Test
    void isResolvable_shortCircuitsOnFirstMatch() {
        int[] queriesMade = {0};
        adapter.nameserverDiscovery = zone -> List.of("ns1.test", "ns2.test", "ns3.test");
        adapter.recordQueryAtNameserver = (fqdn, ns) -> {
            queriesMade[0]++;
            return "ns1.test".equals(ns);
        };

        assertThat(adapter.isResolvable("app.example.com")).isTrue();
        assertThat(queriesMade[0]).isEqualTo(1);
    }
}
