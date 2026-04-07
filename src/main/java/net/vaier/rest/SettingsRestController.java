package net.vaier.rest;

import net.vaier.application.ExportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.application.service.ImportConfigurationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/settings")
public class SettingsRestController {

    private final ExportConfigurationUseCase exportConfigurationUseCase;
    private final ImportConfigurationUseCase importConfigurationUseCase;
    private final SseEventPublisher sseEventPublisher;

    public SettingsRestController(ExportConfigurationUseCase exportConfigurationUseCase,
                                  ImportConfigurationUseCase importConfigurationUseCase,
                                  SseEventPublisher sseEventPublisher) {
        this.exportConfigurationUseCase = exportConfigurationUseCase;
        this.importConfigurationUseCase = importConfigurationUseCase;
        this.sseEventPublisher = sseEventPublisher;
    }

    @GetMapping(value = "/import/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter importEvents() {
        return sseEventPublisher.subscribe(ImportConfigurationService.IMPORT_TOPIC);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export() {
        try {
            String json = exportConfigurationUseCase.exportConfiguration();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vaier-backup.json\"")
                    .body(json);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResult> importConfig(@RequestBody String jsonContent) {
        ImportResult result = importConfigurationUseCase.importConfiguration(jsonContent);
        if (!result.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
