package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.config.ServiceNames;
import net.vaier.config.SetupStateHolder;
import net.vaier.domain.Lifecycle;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForRestartingContainers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LifecycleService {

    private final ForInitialisingUserService forInitialisingUserService;
    private final ForPersistingUsers forPersistingUsers;
    private final ForRestartingContainers containerRestarter;
    private final ForPersistingDnsRecords forPersistingDnsRecords;
    private final ForInitialisingVpnRouting forInitialisingVpnRouting;
    private final SetupStateHolder setupStateHolder;
    private final ConfigResolver configResolver;

    public LifecycleService(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForRestartingContainers containerRestarter,
        ForPersistingDnsRecords forPersistingDnsRecords,
        ForInitialisingVpnRouting forInitialisingVpnRouting,
        SetupStateHolder setupStateHolder,
        ConfigResolver configResolver
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.containerRestarter = containerRestarter;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
        this.forInitialisingVpnRouting = forInitialisingVpnRouting;
        this.setupStateHolder = setupStateHolder;
        this.configResolver = configResolver;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        if (!setupStateHolder.isConfigured()) {
            log.info("Vaier is not configured. Visit /setup.html to complete first-run setup.");
            return;
        }

        log.info("Application is ready, starting lifecycle...");
        runLifecycle();
    }

    public void runLifecycle() {
        configResolver.reload();
        new Lifecycle(
            forInitialisingUserService,
            forPersistingUsers,
            forPersistingDnsRecords,
            containerRestarter,
            configResolver.getDomain(),
            ServiceNames.DEFAULT_ADMIN_USERNAME,
            ServiceNames.AUTHELIA,
            ServiceNames.VAIER,
            ServiceNames.AUTH
        ).start();

        forInitialisingVpnRouting.setupVpnRouting();
    }
}
