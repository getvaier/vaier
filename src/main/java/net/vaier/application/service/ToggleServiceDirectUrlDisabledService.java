package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.PublishingConstants;
import net.vaier.application.ToggleServiceDirectUrlDisabledUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToggleServiceDirectUrlDisabledService implements ToggleServiceDirectUrlDisabledUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ConfigResolver configResolver;

    @Override
    public void setDirectUrlDisabled(String dnsName, boolean directUrlDisabled) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> dnsName.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot change direct URL setting for built-in service: " + dnsName);
        }
        log.info("Setting directUrlDisabled={} for {}", directUrlDisabled, dnsName);
        forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(dnsName, directUrlDisabled);
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
    }
}
