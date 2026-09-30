package net.vaier.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import net.vaier.application.GetWindowsAppUseCase;
import net.vaier.domain.WindowsApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public door the Windows <b>Vaier app</b> is handed out through (#370): a laptop fetches it before it can sign in. */
@ExtendWith(MockitoExtension.class)
class WindowsAppRestControllerTest {

    private static final String PATH = "/app/windows/Vaier-windows.zip";

    @Mock GetWindowsAppUseCase getWindowsAppUseCase;

    @InjectMocks WindowsAppRestController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static WindowsApp app(byte[] payload) {
        return WindowsApp.of(payload.length, out -> {
            try {
                out.write(payload);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseThrow();
    }

    @Test
    void servesTheZipAsADownload() throws Exception {
        byte[] payload = {'P', 'K', 3, 4, 9};
        when(getWindowsAppUseCase.windowsApp()).thenReturn(Optional.of(app(payload)));

        ResponseEntity<StreamingResponseBody> response = controller.download();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/zip"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"Vaier-windows.zip\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void answersAHeadRequest_soTheLaunchpadCanAskBeforeOffering() throws Exception {
        when(getWindowsAppUseCase.windowsApp()).thenReturn(Optional.of(app(new byte[]{1, 2, 3})));

        MvcResult started = mockMvc.perform(head(PATH)).andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3));
    }

    @Test
    void answersNotFoundWhenTheImageCarriesNoZip() throws Exception {
        when(getWindowsAppUseCase.windowsApp()).thenReturn(Optional.empty());

        mockMvc.perform(get(PATH)).andExpect(status().isNotFound());
        mockMvc.perform(head(PATH)).andExpect(status().isNotFound());
    }
}
