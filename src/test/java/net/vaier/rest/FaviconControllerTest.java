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
        when(faviconFetcher.fetch("sonarr.example.com")).thenReturn(Optional.of(icon));

        ResponseEntity<byte[]> response = controller.getFavicon("sonarr.example.com");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(icon);
    }

    @Test
    void returns404WhenFaviconNotFound() {
        when(faviconFetcher.fetch("unknown.example.com")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.getFavicon("unknown.example.com");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
