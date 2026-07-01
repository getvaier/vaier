package net.vaier.application.service;

import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.Lifecycle;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForResolvingPublicHost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LifecycleService {

    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForInitialisingVpnRouting forInitialisingVpnRouting;
    private final ForResolvingPublicHost publicHostResolver;
    private final SetupStateHolder setupStateHolder;
    private final ConfigResolver configResolver;
    private final SyncLanRoutesUseCase syncLanRoutesUseCase;

    public LifecycleService(
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForInitialisingVpnRouting forInitialisingVpnRouting,
        ForResolvingPublicHost publicHostResolver,
        SetupStateHolder setupStateHolder,
        ConfigResolver configResolver,
        SyncLanRoutesUseCase syncLanRoutesUseCase
    ) {
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forInitialisingVpnRouting = forInitialisingVpnRouting;
        this.publicHostResolver = publicHostResolver;
        this.setupStateHolder = setupStateHolder;
        this.configResolver = configResolver;
        this.syncLanRoutesUseCase = syncLanRoutesUseCase;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        if (!setupStateHolder.isConfigured()) {
            log.info("Vaier is not configured. Set VAIER_DOMAIN in .env and restart the stack. Add VAIER_AWS_KEY + VAIER_AWS_SECRET to enable Route53; otherwise Vaier runs in manual DNS mode.");
            return;
        }

        log.info("Application is ready, starting lifecycle...");
        runLifecycle();
    }

    public void runLifecycle() {
        configResolver.reload();
        new Lifecycle(
            forPersistingDnsRecords,
            publicHostResolver,
            configResolver.getDomain(),
            ServiceNames.VAIER
        ).start();

        forInitialisingVpnRouting.setupVpnRouting();
        syncLanRoutesUseCase.syncLanRoutes();
    }
}
