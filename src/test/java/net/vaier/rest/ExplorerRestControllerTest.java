package net.vaier.rest;

import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DeleteFileUseCase;
import net.vaier.application.DownloadFileUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.ListMachineArchivesUseCase;
import net.vaier.domain.Archive;
import net.vaier.domain.CannotDeleteSftpRootException;
import net.vaier.domain.FileEntry;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SourcePaths;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExplorerRestControllerTest {

    @Mock BrowseFilesUseCase browseFilesUseCase;
    @Mock ListMachineArchivesUseCase listMachineArchivesUseCase;
    @Mock DownloadFileUseCase downloadFileUseCase;
    @Mock DeleteFileUseCase deleteFileUseCase;

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

    @Test
    void get_marksTheThreeShieldStates_backedUp_containsBackedUp_orNeither() {
        // The machine protects exactly /home/geir. An entry AT that path is backedUp; an ancestor /home merely
        // CONTAINS it (half shield); an unrelated /var is neither. All three verdicts are the domain's
        // (SourcePaths.covers / enclosesUnder), rendered per entry — never re-derived in the browser.
        when(browseFilesUseCase.listDirectory("apalveien5", "/", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/", List.of(
                FileEntry.in("/", "home", true, 4096, WHEN),
                FileEntry.in("/", "var", true, 4096, WHEN)),
                SourcePaths.of(List.of("/home/geir"))));
        when(browseFilesUseCase.listDirectory("apalveien5", "/home", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home", List.of(
                FileEntry.in("/home", "geir", true, 4096, WHEN)),
                SourcePaths.of(List.of("/home/geir"))));

        List<FileEntryResponse> root = controller.list("apalveien5", "/", null).getBody().entries();
        FileEntryResponse home = root.stream().filter(e -> e.name().equals("home")).findFirst().orElseThrow();
        FileEntryResponse var = root.stream().filter(e -> e.name().equals("var")).findFirst().orElseThrow();
        // /home contains a source path deeper down but is not itself backed up -> half shield.
        assertThat(home.backedUp()).isFalse();
        assertThat(home.containsBackedUp()).isTrue();
        // /var has nothing protected -> neither.
        assertThat(var.backedUp()).isFalse();
        assertThat(var.containsBackedUp()).isFalse();

        // The entry that IS the source path -> fully backed up, and never also "contains" (mutually exclusive).
        FileEntryResponse geir = controller.list("apalveien5", "/home", null).getBody().entries().getFirst();
        assertThat(geir.backedUp()).isTrue();
        assertThat(geir.containsBackedUp()).isFalse();
    }

    @Test
    void get_inThePast_marksNothingBackedUp() {
        // An archived listing carries an empty protected set — the past's backup shape is not today's, so no
        // entry is marked, whatever its path.
        when(browseFilesUseCase.listDirectory("apalveien5", "/home/geir", "ab12"))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home/geir",
                List.of(FileEntry.in("/home/geir", "docs", true, 4096, WHEN)), "ab12"));

        FileEntryResponse entry = controller.list("apalveien5", "/home/geir", "ab12").getBody().entries().getFirst();

        assertThat(entry.backedUp()).isFalse();
        assertThat(entry.containsBackedUp()).isFalse();
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

    // --- slice 5: delete (present-only, destructive) ---------------------------------------------------

    @Test
    void delete_removesThePath_andAnswers204NoContent() {
        ResponseEntity<Void> response = controller.delete("apalveien5", "/home/geir/old");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(deleteFileUseCase).delete("apalveien5", "/home/geir/old");
    }

    @Test
    void delete_passesThePathThroughVerbatim_asTheQueryParameter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(delete("/machines/apalveien5/files").param("path", "/var/tmp/junk"))
            .andExpect(status().isNoContent());

        // The path is handed to the domain verbatim — the controller does not sanitise it, the domain does.
        verify(deleteFileUseCase).delete("apalveien5", "/var/tmp/junk");
    }

    @Test
    void delete_ofTheSftpRoot_isABadRequest_carryingTheGuardSentence() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new CannotDeleteSftpRootException("/volume1")).when(deleteFileUseCase).delete("NAS", "/volume1");

        mockMvc.perform(delete("/machines/NAS/files").param("path", "/volume1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(
                "Refusing to delete /volume1: it is this machine's SFTP root, the whole browsable file tree."));
    }

    @Test
    void delete_ofAPathThatIsNotThere_isANotFound() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new NotFoundException("No such directory: /home/geir/ghost on apalveien5"))
            .when(deleteFileUseCase).delete("apalveien5", "/home/geir/ghost");

        mockMvc.perform(delete("/machines/apalveien5/files").param("path", "/home/geir/ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_thatTheSshUserMayNotPerform_isForbidden() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new PermissionDeniedException("Not allowed to read /root as geir."))
            .when(deleteFileUseCase).delete("apalveien5", "/root");

        mockMvc.perform(delete("/machines/apalveien5/files").param("path", "/root"))
            .andExpect(status().isForbidden());
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
