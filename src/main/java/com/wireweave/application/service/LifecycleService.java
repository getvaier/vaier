package com.wireweave.application.service;

import com.wireweave.domain.Lifecycle;
import com.wireweave.domain.port.ForInitialisingUserService;
import com.wireweave.domain.port.ForPersistingUsers;
import com.wireweave.domain.port.ForRestartingContainers;
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


    public LifecycleService(
        ForInitialisingUserService forInitialisingUserService,
        ForPersistingUsers forPersistingUsers, ForRestartingContainers containerRestarter
    ) {
        this.forInitialisingUserService = forInitialisingUserService;
        this.forPersistingUsers = forPersistingUsers;
        this.containerRestarter = containerRestarter;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        log.info("Application is ready, starting lifecycle...");

        new Lifecycle(
            forInitialisingUserService,
            forPersistingUsers,
            containerRestarter
        ).start();
    }
}
