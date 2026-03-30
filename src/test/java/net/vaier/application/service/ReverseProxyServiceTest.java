package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase.ReverseProxyRouteUco;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReverseProxyServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @InjectMocks
    ReverseProxyService service;

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

    @Test
    void deleteReverseProxyRoute_delegatesDeleteByDnsName() {
        service.deleteReverseProxyRoute("app.example.com");

        verify(forPersistingReverseProxyRoutes).deleteReverseProxyRouteByDnsName("app.example.com");
    }
}
