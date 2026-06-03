package net.vaier.rest;

import net.vaier.application.GetOfflinePageUseCase;
import net.vaier.application.GetOfflinePageUseCase.OfflinePage;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves Vaier's branded offline page. Traefik's {@code errors} middleware calls this endpoint
 * directly (bypassing routers and auth) when a published service's backend returns 502/503/504,
 * passing the failed request's {@code X-Forwarded-Host} so the page can name the service.
 */
@RestController
public class OfflinePageController {

    private final GetOfflinePageUseCase getOfflinePageUseCase;

    public OfflinePageController(GetOfflinePageUseCase getOfflinePageUseCase) {
        this.getOfflinePageUseCase = getOfflinePageUseCase;
    }

    @GetMapping("/error-pages/{status}")
    public ResponseEntity<String> offlinePage(
            @PathVariable int status,
            @RequestHeader(value = "X-Forwarded-Host", required = false) String forwardedHost) {
        OfflinePage page = getOfflinePageUseCase.render(status, forwardedHost);
        return ResponseEntity.status(page.status())
                .contentType(MediaType.parseMediaType(page.contentType()))
                .body(page.html());
    }
}
