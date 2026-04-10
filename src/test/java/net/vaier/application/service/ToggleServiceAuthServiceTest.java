package net.vaier.application.service;

import net.vaier.application.ForInvalidatingPublishedServicesCache;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToggleServiceAuthServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    ToggleServiceAuthService service;

    @BeforeEach
    void setUp() {
        when(configResolver.getDomain()).thenReturn("example.com");
    }

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

    @Test
    void setAuthentication_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.setAuthentication("vaier.example.com", true));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void setAuthentication_rejectsAuthService() {
        assertThrows(IllegalArgumentException.class, () -> service.setAuthentication("login.example.com", false));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void setAuthentication_invalidatesPublishedServicesCache() {
        service.setAuthentication("app.example.com", true);

        verify(forInvalidatingPublishedServicesCache).invalidatePublishedServicesCache();
    }
}
