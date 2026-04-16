package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.application.PublishingConstants;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToggleServiceAuthService implements ToggleServiceAuthUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;
    private final ConfigResolver configResolver;

    @Override
    public void setAuthentication(String dnsName, boolean requiresAuth) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> dnsName.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot change auth for built-in service: " + dnsName);
        }
        log.info("Setting auth={} for {}", requiresAuth, dnsName);
        forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, requiresAuth);
        forInvalidatingPublishedServicesCache.invalidatePublishedServicesCache();
    }
}
