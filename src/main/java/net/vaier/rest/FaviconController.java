package net.vaier.rest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FaviconController {

    private final FaviconFetcherService faviconFetcherService;

    public FaviconController(FaviconFetcherService faviconFetcherService) {
        this.faviconFetcherService = faviconFetcherService;
    }

    @GetMapping("/favicon")
    public ResponseEntity<byte[]> getFavicon(@RequestParam String host) {
        return faviconFetcherService.fetch(host)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(detectContentType(bytes))
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MediaType detectContentType(byte[] bytes) {
        if (bytes.length >= 4 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return MediaType.IMAGE_PNG;
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return MediaType.IMAGE_GIF;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return MediaType.IMAGE_JPEG;
        if (bytes.length >= 4 && bytes[0] == '<') return MediaType.valueOf("image/svg+xml");
        return MediaType.valueOf("image/x-icon");
    }
}
