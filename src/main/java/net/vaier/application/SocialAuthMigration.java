package net.vaier.application;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-off, idempotent startup migration that flips every remaining {@link AuthMode#AUTHELIA}
 * published route over to {@link AuthMode#SOCIAL} (Google via oauth2-proxy) — the #305 rollout step
 * that moves all services off Authelia forward-auth in one go. {@link AuthMode#NONE} and
 * {@link AuthMode#SOCIAL} routes are left untouched, so a second run flips nothing (there are no
 * Authelia routes left). Which mode a route carries is a domain decision
 * ({@link ReverseProxyRoute#authMode()}); this component only orchestrates through the
 * {@link ForPersistingReverseProxyRoutes} port, whose {@code setRouteAuthMode} also stands up the
 * per-host {@code /oauth2/} helper router. Mirrors the {@code *OnStartup} migration pattern.
 */
@Component
@Slf4j
public class SocialAuthMigration {

    private final ForPersistingReverseProxyRoutes routes;

    public SocialAuthMigration(ForPersistingReverseProxyRoutes routes) {
        this.routes = routes;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAutheliaRoutesToSocialOnStartup() {
        try {
            int flipped = 0;
            for (ReverseProxyRoute route : routes.getReverseProxyRoutes()) {
                if (route.authMode() == AuthMode.AUTHELIA) {
                    routes.setRouteAuthMode(route.getDomainName(), route.getPathPrefix(), AuthMode.SOCIAL);
                    flipped++;
                }
            }
            if (flipped > 0) {
                log.info("Migrated {} Authelia-gated published route(s) to social login", flipped);
            }
        } catch (Exception e) {
            log.warn("Authelia-to-social route migration on startup failed", e);
        }
    }
}
