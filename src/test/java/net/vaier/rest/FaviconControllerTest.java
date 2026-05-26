package net.vaier.rest;

import net.vaier.application.GetFaviconUseCase;
import net.vaier.application.GetFaviconUseCase.Favicon;
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
class FaviconControllerTest {

    @Mock GetFaviconUseCase getFaviconUseCase;

    @InjectMocks FaviconController controller;

    @Test
    void returns200WithBytesAndDeclaredContentType() {
        byte[] icon = {0, 0, 1, 0};
        when(getFaviconUseCase.getFavicon("sonarr.example.com", null))
            .thenReturn(Optional.of(new Favicon(icon, "image/x-icon")));

        ResponseEntity<byte[]> response = controller.getFavicon("sonarr.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(icon);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("image/x-icon"));
    }

    @Test
    void returns404WhenUseCaseReturnsEmpty() {
        when(getFaviconUseCase.getFavicon("unknown.example.com", null)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getFavicon("unknown.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void passesPathPrefixThroughToUseCase() {
        byte[] icon = {(byte) 0x89, 'P', 'N', 'G'};
        when(getFaviconUseCase.getFavicon("services.example.com", "/grafana"))
            .thenReturn(Optional.of(new Favicon(icon, "image/png")));

        ResponseEntity<byte[]> response = controller.getFavicon("services.example.com", "/grafana");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }
}
