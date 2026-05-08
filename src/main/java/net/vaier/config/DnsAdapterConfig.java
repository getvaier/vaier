package net.vaier.config;

import net.vaier.adapter.driven.ManualDnsAdapter;
import net.vaier.adapter.driven.Route53DnsAdapter;
import net.vaier.domain.DnsProvider;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForValidatingAwsCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class DnsAdapterConfig {

    @Bean
    public ForPersistingDnsRecords dnsRecordsAdapter(ConfigResolver configResolver) {
        return configResolver.getDnsProvider() == DnsProvider.MANUAL
            ? new ManualDnsAdapter(configResolver)
            : new Route53DnsAdapter(configResolver);
    }

    @Bean
    @Primary
    public ForValidatingAwsCredentials awsCredentialsValidator(
        ConfigResolver configResolver,
        ForPersistingDnsRecords dnsRecordsAdapter
    ) {
        if (dnsRecordsAdapter instanceof Route53DnsAdapter route53) {
            return route53;
        }
        return new Route53DnsAdapter(configResolver);
    }
}
