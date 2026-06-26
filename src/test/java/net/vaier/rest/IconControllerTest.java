package net.vaier.rest;

import net.vaier.application.GetIconUseCase;
import net.vaier.application.GetIconUseCase.Icon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IconControllerTest {

    @Mock GetIconUseCase getIconUseCase;

    @InjectMocks IconController controller;

    @Test
    void returns200WithBytesAndDeclaredContentType() {
        byte[] icon = {0, 0, 1, 0};
        when(getIconUseCase.getIcon("sonarr.example.com", null))
            .thenReturn(Optional.of(new Icon(icon, "image/x-icon")));

        ResponseEntity<byte[]> response = controller.getIcon("sonarr.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(icon);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/x-icon"));
    }

    @Test
    void returns404WhenUseCaseReturnsEmpty() {
        when(getIconUseCase.getIcon("unknown.example.com", null)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getIcon("unknown.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void passesPathPrefixThroughToUseCase() {
        byte[] icon = {(byte) 0x89, 'P', 'N', 'G'};
        when(getIconUseCase.getIcon("services.example.com", "/grafana"))
            .thenReturn(Optional.of(new Icon(icon, "image/png")));

        ResponseEntity<byte[]> response = controller.getIcon("services.example.com", "/grafana");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }
}
