package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerReachabilityUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LanServerReachabilityScheduler {

    private final GetLanServerReachabilityUseCase reachabilityUseCase;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void refresh() {
        try {
            reachabilityUseCase.refreshAll();
        } catch (Exception e) {
            log.debug("LAN server reachability refresh failed: {}", e.getMessage());
        }
    }
}
