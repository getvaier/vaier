package net.vaier.application.service;

import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LifecycleServiceTest {

    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForInitialisingVpnRouting forInitialisingVpnRouting;
    @Mock ForResolvingPublicHost publicHostResolver;
    @Mock SetupStateHolder setupStateHolder;
    @Mock ConfigResolver configResolver;
    @Mock SyncLanRoutesUseCase syncLanRoutesUseCase;
    @Mock ApplicationReadyEvent event;

    private LifecycleService service() {
        return new LifecycleService(
            forPersistingDnsRecords,
            forInitialisingVpnRouting,
            publicHostResolver,
            setupStateHolder,
            configResolver,
            syncLanRoutesUseCase
        );
    }

    @Test
    void skipsLifecycleWhenUnconfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        service().handle(event);

        verify(forInitialisingVpnRouting, never()).setupVpnRouting();
        verify(syncLanRoutesUseCase, never()).syncLanRoutes();
    }

    @Test
    void runsSyncLanRoutesOnReadyWhenConfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
        when(configResolver.getDomain()).thenReturn("example.com");
        when(forPersistingDnsRecords.getDnsZones())
            .thenReturn(java.util.List.of(new net.vaier.domain.DnsZone("example.com")));
        when(forPersistingDnsRecords.getDnsRecords(any())).thenReturn(java.util.List.of());
        when(publicHostResolver.resolve()).thenReturn(java.util.Optional.empty());

        service().handle(event);

        verify(syncLanRoutesUseCase).syncLanRoutes();
    }
}
