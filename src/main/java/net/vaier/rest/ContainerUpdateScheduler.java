package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CheckContainerUpdatesUseCase;
import net.vaier.application.ForPublishingEvents;
import net.vaier.domain.ContainerUpdateStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ContainerUpdateScheduler {

    private final CheckContainerUpdatesUseCase checkContainerUpdatesUseCase;
    private final ForPublishingEvents eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 86400000, initialDelay = 30000)
    public void checkForUpdates() {
        try {
            List<ContainerUpdateStatus> results = checkContainerUpdatesUseCase.checkAll();
            String json = objectMapper.writeValueAsString(results);
            eventPublisher.publish("vpn-peers", "container-updates", json);
            log.info("Published container update check: {} images checked, {} updates available",
                    results.size(), results.stream().filter(ContainerUpdateStatus::updateAvailable).count());
        } catch (Exception e) {
            log.debug("Failed to check container updates: {}", e.getMessage());
        }
    }
}
