package net.vaier.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToggleServiceAuthService implements ToggleServiceAuthUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Override
    public void setAuthentication(String dnsName, boolean requiresAuth) {
        log.info("Setting auth={} for {}", requiresAuth, dnsName);
        forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, requiresAuth);
    }
}
