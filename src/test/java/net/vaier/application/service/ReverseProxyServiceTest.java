package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase.ReverseProxyRouteUco;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReverseProxyServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

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
}
