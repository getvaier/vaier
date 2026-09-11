package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase.ReverseProxyRouteUco;
import net.vaier.domain.ReverseProxyAuditState;
import net.vaier.domain.ReverseProxyAuditTracker;
import net.vaier.domain.ReverseProxyConfig;
import net.vaier.domain.ReverseProxyFinding;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyAuditState;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForReadingReverseProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReverseProxyServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForReadingReverseProxyConfig forReadingReverseProxyConfig;

    @Mock
    ForPersistingReverseProxyAuditState reverseProxyAuditState;

    @InjectMocks
    ReverseProxyService service;

    // --- add: happy path / delegation ---

    @Test
    void addReverseProxyRoute_callsPortWithCorrectArgsAndNullRedirectPath() {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("app.example.com", "192.168.1.10", 8080, true);

        service.addReverseProxyRoute(uco);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "192.168.1.10", 8080, true, null
        );
    }

    @Test
    void addReverseProxyRoute_noAuth_callsPortWithAuthFalse() {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("open.example.com", "10.0.0.5", 3000, false);

        service.addReverseProxyRoute(uco);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "open.example.com", "10.0.0.5", 3000, false, null
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 80, 443, 8080, 65535})
    void addReverseProxyRoute_acceptsValidPorts(int port) {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("app.example.com", "10.0.0.5", port, false);

        service.addReverseProxyRoute(uco);

        verify(forPersistingReverseProxyRoutes).addReverseProxyRoute(
            "app.example.com", "10.0.0.5", port, false, null
        );
    }

    @Test
    void addReverseProxyRoute_propagatesDuplicateRouteException() {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("app.example.com", "10.0.0.5", 8080, false);
        doThrow(new RuntimeException("Route already exists: app.example.com"))
                .when(forPersistingReverseProxyRoutes)
                .addReverseProxyRoute("app.example.com", "10.0.0.5", 8080, false, null);

        assertThatThrownBy(() -> service.addReverseProxyRoute(uco))
                .isInstanceOf(RuntimeException.class);
    }

    // --- add: dnsName validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void addReverseProxyRoute_rejectsBlankDnsName(String dnsName) {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco(dnsName, "10.0.0.5", 8080, false);

        assertThatThrownBy(() -> service.addReverseProxyRoute(uco))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    // --- add: target address validation ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void addReverseProxyRoute_rejectsBlankAddress(String address) {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("app.example.com", address, 8080, false);

        assertThatThrownBy(() -> service.addReverseProxyRoute(uco))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("address");

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    // --- add: port range validation ---

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, 65536, 70000, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void addReverseProxyRoute_rejectsOutOfRangePort(int port) {
        ReverseProxyRouteUco uco = new ReverseProxyRouteUco("app.example.com", "10.0.0.5", port, false);

        assertThatThrownBy(() -> service.addReverseProxyRoute(uco))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    // --- delete ---

    @Test
    void deleteReverseProxyRoute_delegatesDeleteByDnsName() {
        service.deleteReverseProxyRoute("app.example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
    }

    @Test
    void deleteReverseProxyRoute_propagatesRouteNotFoundException() {
        doThrow(new RuntimeException("Router not found: app.example.com"))
                .when(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");

        assertThatThrownBy(() -> service.deleteReverseProxyRoute("app.example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void deleteReverseProxyRoute_rejectsBlankDnsName(String dnsName) {
        assertThatThrownBy(() -> service.deleteReverseProxyRoute(dnsName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dnsName");

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    // --- get ---

    @Test
    void getReverseProxyRoutes_returnsPortResult() {
        List<ReverseProxyRoute> routes = List.of(
            new ReverseProxyRoute("route", "app.example.com", "10.0.0.1", 8080, "svc", null),
            new ReverseProxyRoute("route", "db.example.com", "10.0.0.2", 5432, "svc", null)
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(routes);

        assertThat(service.getReverseProxyRoutes()).isSameAs(routes);
    }

    @Test
    void getReverseProxyRoutes_emptyList_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getReverseProxyRoutes()).isEmpty();
    }

    // --- the reverse proxy audit (#354): Vaier judging the config it writes itself ---

    @Test
    void getReverseProxyAudit_judgesWhatTheConfigPortHandsBack() {
        when(forReadingReverseProxyConfig.getReverseProxyConfig()).thenReturn(
            ReverseProxyConfig.builder()
                .middlewares(List.of(ReverseProxyConfig.ConfiguredMiddleware.builder()
                    .protocol(ReverseProxyConfig.Protocol.HTTP).name("orphaned-redirect").build()))
                .build());

        assertThat(service.getReverseProxyAudit().findings())
            .extracting(ReverseProxyFinding::entryName)
            .containsExactly("orphaned-redirect");
    }

    @Test
    void getReverseProxyAudit_neverTouchesWhatAdminsWereTold() {
        // Opening Settings must not be able to move a notification latch.
        when(forReadingReverseProxyConfig.getReverseProxyConfig())
            .thenReturn(ReverseProxyConfig.empty());

        service.getReverseProxyAudit();

        verifyNoInteractions(reverseProxyAuditState);
    }

    @Test
    void auditReverseProxyConfig_alertsTheFirstTimeItFindsSomething() {
        when(forReadingReverseProxyConfig.getReverseProxyConfig()).thenReturn(
            ReverseProxyConfig.builder()
                .middlewares(List.of(ReverseProxyConfig.ConfiguredMiddleware.builder()
                    .protocol(ReverseProxyConfig.Protocol.HTTP).name("orphaned-redirect").build()))
                .build());
        when(reverseProxyAuditState.find()).thenReturn(Optional.empty());

        ReverseProxyAuditTracker.Verdict verdict = service.auditReverseProxyConfig();

        assertThat(verdict.outcome()).isEqualTo(ReverseProxyAuditTracker.Outcome.ALERT);
        verify(reverseProxyAuditState).save(any(ReverseProxyAuditState.class));
    }

    @Test
    void auditReverseProxyConfig_staysQuietOnAConfigThatWasNeverBroken() {
        when(forReadingReverseProxyConfig.getReverseProxyConfig())
            .thenReturn(ReverseProxyConfig.empty());
        when(reverseProxyAuditState.find()).thenReturn(Optional.empty());

        assertThat(service.auditReverseProxyConfig().outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.QUIET);
    }
}
