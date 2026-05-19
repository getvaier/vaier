package net.vaier.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaviconControllerTest {

    @Mock
    FaviconFetcher faviconFetcher;

    @InjectMocks
    FaviconController controller;

    @Test
    void returns200WithBytesWhenFaviconFound() {
        byte[] icon = {0, 0, 1, 0};
        when(faviconFetcher.fetch("sonarr.example.com", null)).thenReturn(Optional.of(icon));

        ResponseEntity<byte[]> response = controller.getFavicon("sonarr.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(icon);
    }

    @Test
    void returns404WhenFaviconNotFound() {
        when(faviconFetcher.fetch("unknown.example.com", null)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getFavicon("unknown.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void passesPathPrefixThroughToFetcher() {
        byte[] icon = {(byte) 0x89, 'P', 'N', 'G'};
        when(faviconFetcher.fetch("services.example.com", "/grafana")).thenReturn(Optional.of(icon));

        ResponseEntity<byte[]> response = controller.getFavicon("services.example.com", "/grafana");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(icon);
    }
}
