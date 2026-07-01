package net.vaier.application;

import net.vaier.domain.AuthMode;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialAuthMigrationTest {

    @Mock
    private ForPersistingReverseProxyRoutes routes;

    /**
     * Build a route whose {@link ReverseProxyRoute#authMode()} reads as {@code mode} — the mode is
     * derived from the middleware chain, so we seed the chain each mode emits.
     */
    private static ReverseProxyRoute routeIn(AuthMode mode, String dnsName, String pathPrefix) {
        return new ReverseProxyRoute(
                ReverseProxyRoute.routerName(dnsName, pathPrefix), dnsName, "10.0.0.1", 8080,
                ReverseProxyRoute.serviceName(dnsName, pathPrefix), null, null, null,
                mode.authMiddlewareNames(), null, false, false, "http", pathPrefix);
    }

    @Test
    void flipsOnlyAutheliaRoutesToSocial() {
        when(routes.getReverseProxyRoutes()).thenReturn(List.of(
                routeIn(AuthMode.AUTHELIA, "grafana.example.com", null),
                routeIn(AuthMode.AUTHELIA, "app.example.com", "/admin"),
                routeIn(AuthMode.SOCIAL, "console.example.com", null),
                routeIn(AuthMode.NONE, "public.example.com", null)));

        new SocialAuthMigration(routes).migrateAutheliaRoutesToSocialOnStartup();

        verify(routes).setRouteAuthMode("grafana.example.com", null, AuthMode.SOCIAL);
        verify(routes).setRouteAuthMode("app.example.com", "/admin", AuthMode.SOCIAL);
        verify(routes, times(2)).setRouteAuthMode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(AuthMode.SOCIAL));
        verify(routes, never()).setRouteAuthMode(
                org.mockito.ArgumentMatchers.eq("console.example.com"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(routes, never()).setRouteAuthMode(
                org.mockito.ArgumentMatchers.eq("public.example.com"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void isIdempotent_whenNoAutheliaRoutesRemainNothingIsFlipped() {
        when(routes.getReverseProxyRoutes()).thenReturn(List.of(
                routeIn(AuthMode.SOCIAL, "console.example.com", null),
                routeIn(AuthMode.NONE, "public.example.com", null)));

        new SocialAuthMigration(routes).migrateAutheliaRoutesToSocialOnStartup();

        verify(routes, never()).setRouteAuthMode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyRouteList_noCallsNoError() {
        when(routes.getReverseProxyRoutes()).thenReturn(List.of());

        new SocialAuthMigration(routes).migrateAutheliaRoutesToSocialOnStartup();

        verify(routes).getReverseProxyRoutes();
        verify(routes, never()).setRouteAuthMode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
