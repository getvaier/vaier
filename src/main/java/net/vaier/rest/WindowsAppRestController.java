package net.vaier.rest;

import net.vaier.application.GetWindowsAppUseCase;
import net.vaier.domain.WindowsApp;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Hands out the Windows <b>Vaier app</b> (#370). Unauthenticated on the public Traefik router for the
 * Android package's reason: a laptop fetches the app before it can sign in, and the zip holds no fleet
 * secret. 404 when the image carries none; HEAD is answered on the same mapping.
 */
@RestController
public class WindowsAppRestController {

    private final GetWindowsAppUseCase getWindowsAppUseCase;

    public WindowsAppRestController(GetWindowsAppUseCase getWindowsAppUseCase) {
        this.getWindowsAppUseCase = getWindowsAppUseCase;
    }

    @GetMapping("/app/windows/Vaier-windows.zip")
    public ResponseEntity<StreamingResponseBody> download() {
        return getWindowsAppUseCase.windowsApp()
            .map(app -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, app.contentDisposition())
                .contentType(MediaType.valueOf(WindowsApp.CONTENT_TYPE))
                .contentLength(app.sizeBytes())
                .body((StreamingResponseBody) app::writeTo))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
