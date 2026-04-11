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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EditServiceRedirectServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    ForInvalidatingPublishedServicesCache forInvalidatingPublishedServicesCache;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    EditServiceRedirectService service;

    @BeforeEach
    void setUp() {
        when(configResolver.getDomain()).thenReturn("example.com");
    }

    @Test
    void setRootRedirectPath_delegatesToPort() {
        service.setRootRedirectPath("app.example.com", "/dashboard/");

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", "/dashboard/");
    }

    @Test
    void setRootRedirectPath_nullPath_removesRedirect() {
        service.setRootRedirectPath("app.example.com", null);

        verify(forPersistingReverseProxyRoutes).setRouteRootRedirectPath("app.example.com", null);
    }

    @Test
    void setRootRedirectPath_invalidatesCache() {
        service.setRootRedirectPath("app.example.com", "/dashboard/");

        verify(forInvalidatingPublishedServicesCache).invalidatePublishedServicesCache();
    }

    @Test
    void setRootRedirectPath_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class,
            () -> service.setRootRedirectPath("vaier.example.com", "/admin/"));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void setRootRedirectPath_rejectsAuthService() {
        assertThrows(IllegalArgumentException.class,
            () -> service.setRootRedirectPath("login.example.com", "/admin/"));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }
}
