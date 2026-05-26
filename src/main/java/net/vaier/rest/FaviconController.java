package net.vaier.rest;

import net.vaier.application.GetFaviconUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FaviconController {

    private final GetFaviconUseCase getFaviconUseCase;

    public FaviconController(GetFaviconUseCase getFaviconUseCase) {
        this.getFaviconUseCase = getFaviconUseCase;
    }

    @GetMapping("/favicon")
    public ResponseEntity<byte[]> getFavicon(
            @RequestParam String host,
            @RequestParam(required = false) String pathPrefix) {
        return getFaviconUseCase.getFavicon(host, pathPrefix)
                .map(f -> ResponseEntity.ok()
                        .contentType(MediaType.valueOf(f.contentType()))
                        .body(f.body()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
