package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForWritingBootstrapCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LifecycleServiceTest {

    @Mock ForInitialisingUserService forInitialisingUserService;
    @Mock ForPersistingUsers forPersistingUsers;
    @Mock ForRestartingContainers containerRestarter;
    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForInitialisingVpnRouting forInitialisingVpnRouting;
    @Mock ForWritingBootstrapCredentials bootstrapCredentialsWriter;
    @Mock SetupStateHolder setupStateHolder;
    @Mock ConfigResolver configResolver;
    @Mock ApplicationReadyEvent event;

    private LifecycleService service() {
        return new LifecycleService(
            forInitialisingUserService, forPersistingUsers,
            containerRestarter, forPersistingDnsRecords,
            forInitialisingVpnRouting, bootstrapCredentialsWriter,
            setupStateHolder, configResolver
        );
    }

    @Test
    void skipsLifecycleWhenUnconfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        service().handle(event);

        verify(forInitialisingVpnRouting, never()).setupVpnRouting();
        verify(forInitialisingUserService, never()).initialiseConfiguration();
    }
}
