package net.vaier.application.service;

import net.vaier.application.PublishedServicesCacheInvalidator;
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
class ToggleServiceDirectUrlDisabledServiceTest {

    @Mock
    ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;

    @Mock
    PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    ToggleServiceDirectUrlDisabledService service;

    @BeforeEach
    void setUp() {
        when(configResolver.getDomain()).thenReturn("example.com");
    }

    @Test
    void setDirectUrlDisabled_true_delegatesToPort() {
        service.setDirectUrlDisabled("app.example.com", true);

        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", true);
    }

    @Test
    void setDirectUrlDisabled_false_delegatesToPort() {
        service.setDirectUrlDisabled("app.example.com", false);

        verify(forPersistingReverseProxyRoutes).setRouteDirectUrlDisabled("app.example.com", false);
    }

    @Test
    void setDirectUrlDisabled_rejectsVaierService() {
        assertThrows(IllegalArgumentException.class, () -> service.setDirectUrlDisabled("vaier.example.com", true));

        verifyNoInteractions(forPersistingReverseProxyRoutes);
    }

    @Test
    void setDirectUrlDisabled_invalidatesPublishedServicesCache() {
        service.setDirectUrlDisabled("app.example.com", true);

        verify(publishedServicesCacheInvalidator).invalidatePublishedServicesCache();
    }
}
