package net.vaier.rest;

import net.vaier.application.GetIconUseCase;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IconController {

    private final GetIconUseCase getIconUseCase;

    public IconController(GetIconUseCase getIconUseCase) {
        this.getIconUseCase = getIconUseCase;
    }

    @GetMapping("/icon")
    public ResponseEntity<byte[]> getIcon(
            @RequestParam String host,
            @RequestParam(required = false) String pathPrefix) {
        return getIconUseCase.getIcon(host, pathPrefix)
                .map(f -> ResponseEntity.ok()
                        .contentType(MediaType.valueOf(f.contentType()))
                        .body(f.body()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
