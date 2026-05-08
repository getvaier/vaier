package net.vaier.config;

import net.vaier.adapter.driven.ManualDnsAdapter;
import net.vaier.adapter.driven.Route53DnsAdapter;
import net.vaier.domain.DnsProvider;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DnsAdapterConfigTest {

    @Mock ConfigResolver configResolver;

    @Test
    void picksRoute53AdapterWhenProviderIsRoute53() {
        when(configResolver.getDnsProvider()).thenReturn(DnsProvider.ROUTE53);
        DnsAdapterConfig config = new DnsAdapterConfig();

        ForPersistingDnsRecords adapter = config.dnsRecordsAdapter(configResolver);

        assertThat(adapter).isInstanceOf(Route53DnsAdapter.class);
    }

    @Test
    void picksManualAdapterWhenProviderIsManual() {
        when(configResolver.getDnsProvider()).thenReturn(DnsProvider.MANUAL);
        DnsAdapterConfig config = new DnsAdapterConfig();

        ForPersistingDnsRecords adapter = config.dnsRecordsAdapter(configResolver);

        assertThat(adapter).isInstanceOf(ManualDnsAdapter.class);
    }

    @Test
    void awsCredentialsValidatorReusesRoute53AdapterInRoute53Mode() {
        when(configResolver.getDnsProvider()).thenReturn(DnsProvider.ROUTE53);
        DnsAdapterConfig config = new DnsAdapterConfig();
        var dnsAdapter = config.dnsRecordsAdapter(configResolver);

        ForValidatingAwsCredentials validator = config.awsCredentialsValidator(configResolver, dnsAdapter);

        assertThat(validator).isSameAs(dnsAdapter);
    }

    @Test
    void awsCredentialsValidatorIsRoute53AdapterEvenInManualMode() {
        when(configResolver.getDnsProvider()).thenReturn(DnsProvider.MANUAL);
        DnsAdapterConfig config = new DnsAdapterConfig();
        var dnsAdapter = config.dnsRecordsAdapter(configResolver);

        ForValidatingAwsCredentials validator = config.awsCredentialsValidator(configResolver, dnsAdapter);

        assertThat(validator).isInstanceOf(Route53DnsAdapter.class);
    }
}
