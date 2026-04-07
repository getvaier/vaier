package net.vaier.rest;

import net.vaier.application.ExportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase;
import net.vaier.application.ImportConfigurationUseCase.ImportResult;
import net.vaier.rest.SseEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsRestControllerTest {

    @Mock ExportConfigurationUseCase exportConfigurationUseCase;
    @Mock ImportConfigurationUseCase importConfigurationUseCase;
    @Mock SseEventPublisher sseEventPublisher;

    @InjectMocks
    SettingsRestController controller;

    @Test
    void export_delegatesToUseCaseAndReturnsJson() {
        when(exportConfigurationUseCase.exportConfiguration()).thenReturn("{\"version\":\"1\"}");

        ResponseEntity<String> response = controller.export();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"version\":\"1\"}");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("vaier-backup.json");
    }

    @Test
    void export_returns500WhenUseCaseFails() {
        when(exportConfigurationUseCase.exportConfiguration())
                .thenThrow(new RuntimeException("export failed"));

        ResponseEntity<String> response = controller.export();

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void importConfig_delegatesToUseCaseAndReturnsResult() {
        String json = "{\"version\":\"1\"}";
        ImportResult importResult = new ImportResult(true, "Import completed", List.of());
        when(importConfigurationUseCase.importConfiguration(json)).thenReturn(importResult);

        ResponseEntity<ImportResult> response = controller.importConfig(json);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(importResult);
        verify(importConfigurationUseCase).importConfiguration(json);
    }

    @Test
    void importConfig_returns400WhenImportFails() {
        String json = "not json";
        when(importConfigurationUseCase.importConfiguration(json))
                .thenReturn(new ImportResult(false, "Invalid backup file", List.of()));

        ResponseEntity<ImportResult> response = controller.importConfig(json);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
