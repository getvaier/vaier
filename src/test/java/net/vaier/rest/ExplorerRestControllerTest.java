package net.vaier.rest;

import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.domain.FileEntry;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.SftpRoot;
import net.vaier.rest.ExplorerRestController.DirectoryResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExplorerRestControllerTest {

    @Mock BrowseFilesUseCase browseFilesUseCase;

    @InjectMocks ExplorerRestController controller;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static MachineDirectory at(String path, FileEntry... entries) {
        return new MachineDirectory(SftpRoot.NONE, path, List.of(entries));
    }

    @Test
    void get_listsTheRequestedDirectory_onTheRequestedMachine() {
        when(browseFilesUseCase.listDirectory("apalveien5", "/home/geir")).thenReturn(at("/home/geir",
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN),
            FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)));

        ResponseEntity<DirectoryResponse> response = controller.list("apalveien5", "/home/geir");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        List<FileEntryResponse> body = response.getBody().entries();
        assertThat(body).extracting(FileEntryResponse::name).containsExactly("docs", "notes.txt");
        assertThat(body.getFirst().directory()).isTrue();
        assertThat(body.getFirst().path()).isEqualTo("/home/geir/docs");
        assertThat(body.getLast().size()).isEqualTo(120);
        assertThat(body.getLast().modifiedAt()).isEqualTo("2026-07-13T10:15:30Z");
    }

    @Test
    void get_withNoPath_letsTheMachineSayWhereItsTreeBegins() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory("NAS", null))
            .thenReturn(new MachineDirectory(new SftpRoot("/volume1"), "/volume1",
                List.of(FileEntry.in("/volume1", "homes", true, 4096, WHEN))));

        // No path means "wherever this machine's tree begins" — NOT "/". The browser cannot know that the NAS
        // begins at /volume1 until it has asked, so it must not be made to guess.
        mockMvc.perform(get("/machines/NAS/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.root").value("/volume1"))
            .andExpect(jsonPath("$.path").value("/volume1"))
            .andExpect(jsonPath("$.entries[0].path").value("/volume1/homes"));

        verify(browseFilesUseCase).listDirectory("NAS", null);
    }

    @Test
    void get_carriesTheRootAlongsideTheEntries_soTheBrowserKnowsWhereTheTreeBegins() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(any(), any())).thenReturn(at("/",
            FileEntry.in("/", "etc", true, 4096, WHEN)));

        // The listing is no longer a bare array: an array cannot carry the root, and a machine's file tree
        // begins at its root.
        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.root").value("/"))
            .andExpect(jsonPath("$.path").value("/"))
            .andExpect(jsonPath("$.entries[0].name").value("etc"))
            .andExpect(jsonPath("$.entries[0].path").value("/etc"))
            .andExpect(jsonPath("$.entries[0].directory").value(true))
            .andExpect(jsonPath("$.entries[0].size").value(4096))
            .andExpect(jsonPath("$.entries[0].modifiedAt").value("2026-07-13T10:15:30Z"));
    }

    @Test
    void get_passesTheRequestedPathThrough_asTheQueryParameter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(any(), any())).thenReturn(at("/var/lib"));

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

    @Test
    void get_aPathOutsideTheMachinesSftpRoot_failsWithTheRealSentence_notAnEmptyDirectory() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(browseFilesUseCase.listDirectory(any(), any()))
            .thenThrow(new PathOutsideSftpRootException("/volume2", "/volume1"));

        // /volume2 exists on the NAS — df and the web terminal both see it — but SFTP is chrooted into
        // /volume1 and can never reach it. The operator must be told exactly that, and never be shown an
        // empty folder or, worse, the jail's own contents under another path's name.
        mockMvc.perform(get("/machines/NAS/files").param("path", "/volume2"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PATH_OUTSIDE_SFTP_ROOT"))
            .andExpect(jsonPath("$.message").value(
                "/volume2 is not reachable over SFTP; this machine's SFTP service is rooted at /volume1."));
    }
}
