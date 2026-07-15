package net.vaier.rest;

import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DownloadFileUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.ListMachineArchivesUseCase;
import net.vaier.domain.Archive;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    @Mock ListMachineArchivesUseCase listMachineArchivesUseCase;
    @Mock DownloadFileUseCase downloadFileUseCase;

    @InjectMocks ExplorerRestController controller;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static MachineDirectory at(String path, FileEntry... entries) {
        return new MachineDirectory(SftpRoot.NONE, path, List.of(entries));
    }

    @Test
    void get_listsTheRequestedDirectory_onTheRequestedMachine() {
        when(browseFilesUseCase.listDirectory("apalveien5", "/home/geir", null)).thenReturn(at("/home/geir",
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN),
            FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)));

        ResponseEntity<DirectoryResponse> response = controller.list("apalveien5", "/home/geir", null);

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
        when(browseFilesUseCase.listDirectory("NAS", null, null))
            .thenReturn(new MachineDirectory(new SftpRoot("/volume1"), "/volume1",
                List.of(FileEntry.in("/volume1", "homes", true, 4096, WHEN))));

        // No path means "wherever this machine's tree begins" — NOT "/". The browser cannot know that the NAS
        // begins at /volume1 until it has asked, so it must not be made to guess.
        mockMvc.perform(get("/machines/NAS/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.root").value("/volume1"))
            .andExpect(jsonPath("$.path").value("/volume1"))
            .andExpect(jsonPath("$.entries[0].path").value("/volume1/homes"));

        verify(browseFilesUseCase).listDirectory("NAS", null, null);
    }

    @Test
    void get_carriesTheRootAlongsideTheEntries_soTheBrowserKnowsWhereTheTreeBegins() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(any(), any(), any())).thenReturn(at("/",
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
        when(browseFilesUseCase.listDirectory(any(), any(), any())).thenReturn(at("/var/lib"));

        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/var/lib"))
            .andExpect(status().isOk());

        // The path is handed to the domain verbatim — the controller does not sanitise it, the domain does.
        verify(browseFilesUseCase).listDirectory("apalveien5", "/var/lib", null);
    }

    @Test
    void get_aHostilePath_isRejected_asABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(browseFilesUseCase.listDirectory(any(), any(), any()))
            .thenThrow(new IllegalArgumentException("A path must not climb above the root: /../etc"));

        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/../etc"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_aPathOutsideTheMachinesSftpRoot_failsWithTheRealSentence_notAnEmptyDirectory() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(browseFilesUseCase.listDirectory(any(), any(), any()))
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

    // --- slice D: the time coordinate -------------------------------------------------------------------

    @Test
    void get_withAnArchiveCoordinate_browsesThePast_andCarriesItBack() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory("apalveien5", "/home/geir", "ab12"))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home/geir",
                List.of(FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)), "ab12"));

        mockMvc.perform(get("/machines/apalveien5/files").param("path", "/home/geir").param("at", "ab12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/home/geir"))
            // The listing carries the archive coordinate, so the browser knows it is looking at the past.
            .andExpect(jsonPath("$.at").value("ab12"))
            .andExpect(jsonPath("$.entries[0].path").value("/home/geir/notes.txt"));

        verify(browseFilesUseCase).listDirectory("apalveien5", "/home/geir", "ab12");
    }

    // --- slice 2: download ------------------------------------------------------------------------------

    @Test
    void download_streamsTheFile_asAnAttachment_withItsNameSizeAndBytes() throws Exception {
        byte[] payload = "hello download".getBytes();
        when(downloadFileUseCase.openForDownload("apalveien5", "/home/geir/notes.txt", null))
            .thenReturn(new Download("notes.txt", payload.length, "application/octet-stream", out -> {
                try {
                    out.write(payload);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        // Invoked directly (not via MockMvc) so the streamed body is asserted deterministically, without the
        // async dispatch a StreamingResponseBody otherwise needs.
        ResponseEntity<StreamingResponseBody> response =
            controller.download("apalveien5", "/home/geir/notes.txt", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"notes.txt\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void download_fromAnArchive_isAllowed_becauseADownloadIsARead() throws Exception {
        when(downloadFileUseCase.openForDownload("apalveien5", "/home/geir/notes.txt", "ab12"))
            .thenReturn(new Download("notes.txt", 3, "application/octet-stream", out -> {
                try {
                    out.write("old".getBytes());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.download("apalveien5", "/home/geir/notes.txt", "ab12");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo("old".getBytes());
        verify(downloadFileUseCase).openForDownload("apalveien5", "/home/geir/notes.txt", "ab12");
    }

    @Test
    void download_ofADirectory_streamsAZip_withTheZipContentType_andNoContentLength() throws Exception {
        // The zip is built by the use case; the controller only wires the handle to the response. -1 stands
        // for "not known ahead of time" — a zip's byte count isn't the sum of the files it holds.
        byte[] zipBytes = {1, 2, 3};
        when(downloadFileUseCase.openForDownload("apalveien5", "/home/geir", null))
            .thenReturn(new Download("geir.zip", -1, "application/zip", out -> {
                try {
                    out.write(zipBytes);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        ResponseEntity<StreamingResponseBody> response = controller.download("apalveien5", "/home/geir", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/zip"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"geir.zip\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(zipBytes);
    }

    @Test
    void archives_listsTheMachinesArchives_newestFirst_withIdNameAndTime() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(listMachineArchivesUseCase.listMachineArchives("apalveien5")).thenReturn(List.of(
            new Archive("apalveien5-2026-07-14T02:00:00", "b", Instant.parse("2026-07-14T02:00:00Z")),
            new Archive("apalveien5-2026-07-13T02:00:00", "a", Instant.parse("2026-07-13T02:00:00Z"))));

        mockMvc.perform(get("/machines/apalveien5/archives"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("b"))
            .andExpect(jsonPath("$[0].name").value("apalveien5-2026-07-14T02:00:00"))
            .andExpect(jsonPath("$[0].createdAt").value("2026-07-14T02:00:00Z"))
            .andExpect(jsonPath("$[1].id").value("a"));
    }
}
