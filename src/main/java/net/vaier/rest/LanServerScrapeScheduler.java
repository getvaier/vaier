package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetLanServerScrapeUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LanServerScrapeScheduler {

    private final GetLanServerScrapeUseCase scrapeUseCase;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void refresh() {
        try {
            scrapeUseCase.refreshAll();
        } catch (Exception e) {
            log.debug("LAN server scrape refresh failed: {}", e.getMessage());
        }
    }
}
