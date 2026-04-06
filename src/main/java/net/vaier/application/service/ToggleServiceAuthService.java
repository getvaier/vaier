package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DeleteHostedServiceUseCase;
import net.vaier.application.ForInvalidatingHostedServicesCache;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToggleServiceAuthService implements ToggleServiceAuthUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForInvalidatingHostedServicesCache forInvalidatingHostedServicesCache;

    @Value("${VAIER_DOMAIN:}")
    private String vaierDomain;

    @Override
    public void setAuthentication(String dnsName, boolean requiresAuth) {
        boolean isMandatory = DeleteHostedServiceUseCase.MANDATORY_SUBDOMAINS.stream()
            .anyMatch(sub -> dnsName.equals(sub + "." + vaierDomain));
        if (isMandatory) {
            throw new IllegalArgumentException("Cannot change auth for built-in service: " + dnsName);
        }
        log.info("Setting auth={} for {}", requiresAuth, dnsName);
        forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, requiresAuth);
        forInvalidatingHostedServicesCache.invalidateHostedServicesCache();
    }
}
