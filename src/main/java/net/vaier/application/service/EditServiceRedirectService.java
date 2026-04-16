package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.PublishingConstants;
import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EditServiceRedirectService implements EditServiceRedirectUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;
    private final ConfigResolver configResolver;

    @Override
    public void setRootRedirectPath(String dnsName, String rootRedirectPath) {
        boolean isMandatory = PublishingConstants.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> dnsName.equals(sub + "." + configResolver.getDomain()));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot edit built-in service: " + dnsName);
        }

        log.info("Setting root redirect path for {} to {}", dnsName, rootRedirectPath);
        forPersistingReverseProxyRoutes.setRouteRootRedirectPath(dnsName, rootRedirectPath);
        forInvalidatingPublishedServicesCache.invalidatePublishedServicesCache();
    }
}
