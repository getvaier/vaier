package net.vaier.application.service;

import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetReverseProxyRoutesServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @InjectMocks
    GetReverseProxyRoutesService service;

    @Test
    void getReverseProxyRoutes_returnsPortResult() {
        List<ReverseProxyRoute> routes = List.of(
            route("app.example.com", "10.0.0.1", 8080),
            route("db.example.com", "10.0.0.2", 5432)
        );
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(routes);

        assertThat(service.getReverseProxyRoutes()).isSameAs(routes);
    }

    @Test
    void getReverseProxyRoutes_emptyList_returnsEmpty() {
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        assertThat(service.getReverseProxyRoutes()).isEmpty();
    }

    private ReverseProxyRoute route(String domain, String address, int port) {
        return new ReverseProxyRoute("route", domain, address, port, "svc", null);
    }
}
