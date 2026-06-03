package net.vaier.rest;

import net.vaier.application.GetOfflinePageUseCase;
import net.vaier.application.GetOfflinePageUseCase.OfflinePage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfflinePageControllerTest {

    @Mock GetOfflinePageUseCase getOfflinePageUseCase;

    @InjectMocks OfflinePageController controller;

    @Test
    void respondsWithTheRequestedStatusAndHtmlBody() {
        when(getOfflinePageUseCase.render(502, "foo.example.com"))
            .thenReturn(new OfflinePage(502, "text/html; charset=utf-8", "<html>foo.example.com unavailable</html>"));

        ResponseEntity<String> response = controller.offlinePage(502, "foo.example.com");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).contains("foo.example.com");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
    }

    @Test
    void passesNullHostWhenForwardedHostHeaderAbsent() {
        when(getOfflinePageUseCase.render(503, null))
            .thenReturn(new OfflinePage(503, "text/html; charset=utf-8", "<html>unavailable</html>"));

        ResponseEntity<String> response = controller.offlinePage(503, null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }
}
