package net.vaier.application.service;

import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ToggleServiceAuthServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @InjectMocks
    ToggleServiceAuthService service;

    @Test
    void setAuthentication_true_delegatesToPort() {
        service.setAuthentication("app.example.com", true);

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", true);
    }

    @Test
    void setAuthentication_false_delegatesToPort() {
        service.setAuthentication("app.example.com", false);

        verify(forPersistingReverseProxyRoutes).setRouteAuthentication("app.example.com", false);
    }
}
