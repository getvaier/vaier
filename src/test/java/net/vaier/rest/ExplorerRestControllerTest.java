package net.vaier.rest;

import net.vaier.application.BrowseFilesUseCase;
import net.vaier.domain.FileEntry;
import net.vaier.rest.ExplorerRestController.FileEntryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExplorerRestControllerTest {

    @Mock BrowseFilesUseCase browseFilesUseCase;

    @InjectMocks ExplorerRestController controller;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    @Test
    void get_listsTheRequestedDirectory_onTheRequestedMachine() {
        when(browseFilesUseCase.listDirectory("apalveien5", "/home/geir")).thenReturn(List.of(
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN),
            FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)));

        ResponseEntity<List<FileEntryResponse>> response = controller.list("apalveien5", "/home/geir");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<FileEntryResponse> body = response.getBody();
        assertThat(body).extracting(FileEntryResponse::name).containsExactly("docs", "notes.txt");
        assertThat(body.getFirst().directory()).isTrue();
        assertThat(body.getFirst().path()).isEqualTo("/home/geir/docs");
        assertThat(body.getLast().size()).isEqualTo(120);
        assertThat(body.getLast().modifiedAt()).isEqualTo("2026-07-13T10:15:30Z");
    }

    @Test
    void get_withNoPath_browsesTheRoot() {
        when(browseFilesUseCase.listDirectory("apalveien5", "/")).thenReturn(List.of());

        controller.list("apalveien5", "/");

        verify(browseFilesUseCase).listDirectory("apalveien5", "/");
    }

    @Test
    void get_servesTheListingOverHttp_defaultingToTheRoot() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory("apalveien5", "/")).thenReturn(List.of(
            FileEntry.in("/", "etc", true, 4096, WHEN)));

        // The listing is a bare JSON array — one directory's entries, in listing order.
        mockMvc.perform(get("/machines/apalveien5/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("etc"))
            .andExpect(jsonPath("$[0].path").value("/etc"))
            .andExpect(jsonPath("$[0].directory").value(true))
            .andExpect(jsonPath("$[0].size").value(4096))
            .andExpect(jsonPath("$[0].modifiedAt").value("2026-07-13T10:15:30Z"));
    }

    @Test
    void get_passesTheRequestedPathThrough_asTheQueryParameter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/var/lib"))
            .andExpect(status().isOk());

        // The path is handed to the domain verbatim — the controller does not sanitise it, the domain does.
        verify(browseFilesUseCase).listDirectory("apalveien5", "/var/lib");
    }

    @Test
    void get_aHostilePath_isRejected_asABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(browseFilesUseCase.listDirectory(any(), any()))
            .thenThrow(new IllegalArgumentException("A path must not climb above the root: /../etc"));

        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/../etc"))
            .andExpect(status().isBadRequest());
    }
}
