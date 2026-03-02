package net.vaier.application.service;

import net.vaier.domain.Lifecycle;
import net.vaier.domain.port.ForInitialisingUserService;
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

    public LifecycleService(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers,
        ForRestartingContainers containerRestarter, ForPersistingDnsRecords forPersistingDnsRecords
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.containerRestarter = containerRestarter;
        this.forPersistingDnsRecords = forPersistingDnsRecords;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        log.info("Application is ready, starting lifecycle...");

        new Lifecycle(
            forInitialisingUserService,
            forPersistingUsers,
            forPersistingDnsRecords,
            containerRestarter
        ).start();
    }
}
