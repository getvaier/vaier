package net.vaier.rest;

import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DeleteFileUseCase;
import net.vaier.application.DownloadFileUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import net.vaier.application.ListMachineArchivesUseCase;
import net.vaier.application.UploadFileUseCase;
import net.vaier.application.ViewFileUseCase;
import net.vaier.application.ViewFileUseCase.View;
import net.vaier.domain.Archive;
import net.vaier.domain.CannotDeleteSftpRootException;
import net.vaier.domain.ConflictException;
import net.vaier.domain.EffectiveUserIds;
import net.vaier.domain.Excludes;
import net.vaier.domain.FileEntry;
import net.vaier.domain.FilePermissions;
import net.vaier.domain.FolderAccess;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PathOutsideSftpRootException;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.PermissionDeniedException;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.Selection;
import net.vaier.domain.SftpRoot;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.Upload;
import net.vaier.rest.ExplorerRestController.DirectoryResponse;
import net.vaier.rest.ExplorerRestController.FileEntryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExplorerRestControllerTest {

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock BrowseFilesUseCase browseFilesUseCase;
    @Mock ListMachineArchivesUseCase listMachineArchivesUseCase;
    @Mock DownloadFileUseCase downloadFileUseCase;
    @Mock DeleteFileUseCase deleteFileUseCase;
    @Mock ViewFileUseCase viewFileUseCase;
    @Mock UploadFileUseCase uploadFileUseCase;
    // A real ObjectMapper (spied so @InjectMocks wires it): the selection JSON must be parsed for real.
    @org.mockito.Spy com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks ExplorerRestController controller;

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static MachineDirectory at(String path, FileEntry... entries) {
        return new MachineDirectory(SftpRoot.NONE, path, List.of(entries));
    }

    /** A machine that backs up {@code sources} and excludes nothing. */
    private static ProtectedPaths protecting(String... sources) {
        return ProtectedPaths.of(SourcePaths.of(List.of(sources)), Excludes.none());
    }

    @Test
    void get_listsTheRequestedDirectory_onTheRequestedMachine() {
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home/geir", null)).thenReturn(at("/home/geir",
            FileEntry.in("/home/geir", "docs", true, 4096, WHEN),
            FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)));

        ResponseEntity<DirectoryResponse> response = controller.list(mid("apalveien5").value(), "/home/geir", null);

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
        when(browseFilesUseCase.listDirectory(mid("NAS"), null, null))
            .thenReturn(new MachineDirectory(new SftpRoot("/volume1"), "/volume1",
                List.of(FileEntry.in("/volume1", "homes", true, 4096, WHEN))));

        // No path means "wherever this machine's tree begins" — NOT "/". The browser cannot know that the NAS
        // begins at /volume1 until it has asked, so it must not be made to guess.
        mockMvc.perform(get("/machines/" + mid("NAS") + "/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.root").value("/volume1"))
            .andExpect(jsonPath("$.path").value("/volume1"))
            .andExpect(jsonPath("$.entries[0].path").value("/volume1/homes"));

        verify(browseFilesUseCase).listDirectory(mid("NAS"), null, null);
    }

    @Test
    void get_carriesTheRootAlongsideTheEntries_soTheBrowserKnowsWhereTheTreeBegins() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(any(), any(), any())).thenReturn(at("/",
            FileEntry.in("/", "etc", true, 4096, WHEN)));

        // The listing is no longer a bare array: an array cannot carry the root, and a machine's file tree
        // begins at its root.
        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files").param("path", "/"))
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

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files").param("path", "/var/lib"))
            .andExpect(status().isOk());

        // The path is handed to the domain verbatim — the controller does not sanitise it, the domain does.
        verify(browseFilesUseCase).listDirectory(mid("apalveien5"), "/var/lib", null);
    }

    @Test
    void get_aHostilePath_isRejected_asABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(browseFilesUseCase.listDirectory(any(), any(), any()))
            .thenThrow(new IllegalArgumentException("A path must not climb above the root: /../etc"));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files").param("path", "/../etc"))
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
        mockMvc.perform(get("/machines/" + mid("NAS") + "/files").param("path", "/volume2"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PATH_OUTSIDE_SFTP_ROOT"))
            .andExpect(jsonPath("$.message").value(
                "/volume2 is not reachable over SFTP; this machine's SFTP service is rooted at /volume1."));
    }

    @Test
    void get_forAMachineWithNoStoredCredential_saysSoActionably_notAGeneric500() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        // Vaier reads even its own filesystem over SSH-to-self, so the Vaier server needs a stored credential
        // like any machine. With none, the browse must not dead-end on a generic 500 "unknown error" — it must
        // say exactly what to do, and name the machine so the browser can offer the fix for it.
        when(browseFilesUseCase.listDirectory(any(), any(), any()))
            .thenThrow(new NoHostCredentialException("Vaier server"));

        mockMvc.perform(get("/machines/" + mid("Vaier server") + "/files"))
            .andExpect(status().isFailedDependency())
            .andExpect(jsonPath("$.code").value("NO_CREDENTIAL"))
            .andExpect(jsonPath("$.detail").value("Vaier server"))
            .andExpect(jsonPath("$.message").value(
                "No SSH credential is stored for \"Vaier server\". Add one to browse its files."));
    }

    @Test
    void get_marksTheThreeShieldStates_backedUp_containsBackedUp_orNeither() {
        // The machine protects exactly /home/geir. An entry AT that path is backedUp; an ancestor /home merely
        // CONTAINS it (half shield); an unrelated /var is neither. All three verdicts are the domain's
        // (SourcePaths.covers / enclosesUnder), rendered per entry — never re-derived in the browser.
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/", List.of(
                FileEntry.in("/", "home", true, 4096, WHEN),
                FileEntry.in("/", "var", true, 4096, WHEN)),
                protecting("/home/geir")));
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home", List.of(
                FileEntry.in("/home", "geir", true, 4096, WHEN)),
                protecting("/home/geir")));

        List<FileEntryResponse> root = controller.list(mid("apalveien5").value(), "/", null).getBody().entries();
        FileEntryResponse home = root.stream().filter(e -> e.name().equals("home")).findFirst().orElseThrow();
        FileEntryResponse var = root.stream().filter(e -> e.name().equals("var")).findFirst().orElseThrow();
        // /home contains a source path deeper down but is not itself backed up -> half shield.
        assertThat(home.backedUp()).isFalse();
        assertThat(home.containsBackedUp()).isTrue();
        // /var has nothing protected -> neither.
        assertThat(var.backedUp()).isFalse();
        assertThat(var.containsBackedUp()).isFalse();

        // The entry that IS the source path -> fully backed up, and never also "contains" (mutually exclusive).
        FileEntryResponse geir = controller.list(mid("apalveien5").value(), "/home", null).getBody().entries().getFirst();
        assertThat(geir.backedUp()).isTrue();
        assertThat(geir.containsBackedUp()).isFalse();
    }

    @Test
    void get_marksAnExcludedFolderAsNotBackedUp_evenThoughItsAncestorIsProtected() {
        // "Stop backing up" a folder inside a protected ancestor records it as an exclude. If the shield were
        // still drawn from the source paths alone the folder would keep its full shield, the operator would
        // read the fix as broken, and — worse — believe data is in the archives that borg walks straight past.
        ProtectedPaths protection = ProtectedPaths.of(
            SourcePaths.of(List.of("/home")), Excludes.of(List.of("/home/openhab/userdata/logs")));
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home/openhab/userdata", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home/openhab/userdata", List.of(
                FileEntry.in("/home/openhab/userdata", "logs", true, 4096, WHEN),
                FileEntry.in("/home/openhab/userdata", "jsondb", true, 4096, WHEN)),
                protection));

        List<FileEntryResponse> entries =
            controller.list(mid("apalveien5").value(), "/home/openhab/userdata", null).getBody().entries();
        FileEntryResponse logs = entries.stream().filter(e -> e.name().equals("logs")).findFirst().orElseThrow();
        FileEntryResponse jsondb = entries.stream().filter(e -> e.name().equals("jsondb")).findFirst().orElseThrow();

        assertThat(logs.backedUp()).as("an excluded folder is not backed up").isFalse();
        assertThat(logs.containsBackedUp()).as("and nothing inside it is either").isFalse();
        assertThat(jsondb.backedUp()).as("its siblings are untouched").isTrue();
    }

    @Test
    void get_marksAFolderWithAnExcludedFolderInsideItAsOnlyPartlyBackedUp() {
        // Reported on Colina 27 and Apalveien 5: the openhab logs folder is excluded, yet /home went on wearing
        // a FULL shield. A full shield says "everything under here is in the archive" — with a hole inside it
        // that is a claim about data borg walks straight past. The holed folder now reads half, exactly like a
        // folder that merely contains something protected; its unholed siblings keep their full shield.
        ProtectedPaths protection = ProtectedPaths.of(
            SourcePaths.of(List.of("/home")), Excludes.of(List.of("/home/openhab/userdata/logs")));
        when(browseFilesUseCase.listDirectory(mid("colina27"), "/", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/", List.of(
                FileEntry.in("/", "home", true, 4096, WHEN)), protection));
        when(browseFilesUseCase.listDirectory(mid("colina27"), "/home", null))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home", List.of(
                FileEntry.in("/home", "openhab", true, 4096, WHEN),
                FileEntry.in("/home", "geir", true, 4096, WHEN)), protection));

        FileEntryResponse home = controller.list(mid("colina27").value(), "/", null).getBody().entries().getFirst();
        assertThat(home.backedUp()).as("/home holds a hole, so it is not whole").isFalse();
        assertThat(home.containsBackedUp()).isTrue();

        List<FileEntryResponse> inHome = controller.list(mid("colina27").value(), "/home", null).getBody().entries();
        FileEntryResponse openhab = inHome.stream().filter(e -> e.name().equals("openhab"))
            .findFirst().orElseThrow();
        FileEntryResponse geir = inHome.stream().filter(e -> e.name().equals("geir"))
            .findFirst().orElseThrow();
        assertThat(openhab.backedUp()).as("the hole is on this branch").isFalse();
        assertThat(openhab.containsBackedUp()).isTrue();
        assertThat(geir.backedUp()).as("another branch is untouched by the hole").isTrue();
        assertThat(geir.containsBackedUp()).isFalse();
    }

    @Test
    void get_inThePast_marksNothingBackedUp() {
        // An archived listing carries an empty protected set — the past's backup shape is not today's, so no
        // entry is marked, whatever its path.
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home/geir", "ab12"))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home/geir",
                List.of(FileEntry.in("/home/geir", "docs", true, 4096, WHEN)), "ab12"));

        FileEntryResponse entry = controller.list(mid("apalveien5").value(), "/home/geir", "ab12").getBody().entries().getFirst();

        assertThat(entry.backedUp()).isFalse();
        assertThat(entry.containsBackedUp()).isFalse();
    }

    // --- slice D: the time coordinate -------------------------------------------------------------------

    @Test
    void get_withAnArchiveCoordinate_browsesThePast_andCarriesItBack() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home/geir", "ab12"))
            .thenReturn(new MachineDirectory(SftpRoot.NONE, "/home/geir",
                List.of(FileEntry.in("/home/geir", "notes.txt", false, 120, WHEN)), "ab12"));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files").param("path", "/home/geir").param("at", "ab12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("/home/geir"))
            // The listing carries the archive coordinate, so the browser knows it is looking at the past.
            .andExpect(jsonPath("$.at").value("ab12"))
            .andExpect(jsonPath("$.entries[0].path").value("/home/geir/notes.txt"));

        verify(browseFilesUseCase).listDirectory(mid("apalveien5"), "/home/geir", "ab12");
    }

    // --- slice 2: download ------------------------------------------------------------------------------

    @Test
    void download_streamsTheFile_asAnAttachment_withItsNameSizeAndBytes() throws Exception {
        byte[] payload = "hello download".getBytes();
        when(downloadFileUseCase.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", null))
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
            controller.download(mid("apalveien5").value(), "/home/geir/notes.txt", null, "apalveien5");

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
        when(downloadFileUseCase.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", "ab12"))
            .thenReturn(new Download("notes.txt", 3, "application/octet-stream", out -> {
                try {
                    out.write("old".getBytes());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.download(mid("apalveien5").value(), "/home/geir/notes.txt", "ab12", "apalveien5");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo("old".getBytes());
        verify(downloadFileUseCase).openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/notes.txt", "ab12");
    }

    @Test
    void download_ofADirectory_streamsAZip_withTheZipContentType_andNoContentLength() throws Exception {
        // The zip is built by the use case; the controller only wires the handle to the response. -1 stands
        // for "not known ahead of time" — a zip's byte count isn't the sum of the files it holds.
        byte[] zipBytes = {1, 2, 3};
        when(downloadFileUseCase.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir", null))
            .thenReturn(new Download("geir.zip", -1, "application/zip", out -> {
                try {
                    out.write(zipBytes);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        ResponseEntity<StreamingResponseBody> response = controller.download(mid("apalveien5").value(), "/home/geir", null, "apalveien5");

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
    void download_ofAViewableFile_isStillAnAttachment_andStillOctetStream() throws Exception {
        // The regression guard for Open (#…): adding a way to display a file must not change the way to save
        // one. Download stays attachment/octet-stream for every file, images included — otherwise the button
        // the operator already relies on quietly becomes "opens in a tab".
        when(downloadFileUseCase.openForDownload(mid("apalveien5"), "apalveien5", "/home/geir/holiday.png", null))
            .thenReturn(new Download("holiday.png", 4242, "application/octet-stream", out -> {
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.download(mid("apalveien5").value(), "/home/geir/holiday.png", null, "apalveien5");

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"holiday.png\"");
        // And it never grows the view endpoint's inline-safety headers, which would be meaningless on it.
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }

    // --- Open: displaying a viewable file in the browser ------------------------------------------------

    @Test
    void view_streamsTheFileInline_withTheDomainsMediaType_andTheSafetyHeaders() throws Exception {
        byte[] payload = "PNGBYTES".getBytes();
        when(viewFileUseCase.openForView(mid("apalveien5"), "/home/geir/holiday.png", null))
            .thenReturn(new View("holiday.png", payload.length, "image/png",
                "sandbox; default-src 'none'", out -> {
                    try {
                        out.write(payload);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));

        ResponseEntity<StreamingResponseBody> response =
            controller.view(mid("apalveien5").value(), "/home/geir/holiday.png", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        // inline, not attachment — that is the whole difference from /files/download.
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("inline; filename=\"holiday.png\"");
        // Serving fleet bytes on Vaier's own origin, to a signed-in operator: nosniff so the declared type is
        // the only type, and the domain's policy so nothing in the response can act.
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .isEqualTo("sandbox; default-src 'none'");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(payload);
    }

    @Test
    void view_ofATextFile_isServedAsPlainText() {
        when(viewFileUseCase.openForView(any(), any(), any()))
            .thenReturn(new View("notes.txt", 12, "text/plain;charset=utf-8", "sandbox;", out -> {
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.view(mid("apalveien5").value(), "/home/geir/notes.txt", null);

        assertThat(response.getHeaders().getContentType())
            .isEqualTo(MediaType.valueOf("text/plain;charset=utf-8"));
    }

    @Test
    void view_sanitisesTheFilenameInTheHeader_soItCannotBreakOutOfTheQuotes() {
        when(viewFileUseCase.openForView(any(), any(), any()))
            .thenReturn(new View("ev\"il\r\n.png", 1, "image/png", "sandbox;", out -> {
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.view(mid("apalveien5").value(), "/tmp/x.png", null);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("inline; filename=\"ev_il__.png\"");
    }

    @Test
    void view_withAnArchiveCoordinate_opensThePast_becauseAViewIsARead() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(viewFileUseCase.openForView(mid("apalveien5"), "/home/geir/holiday.png", "ab12"))
            .thenReturn(new View("holiday.png", 3, "image/png", "sandbox;", out -> {
            }));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files/view")
                .param("path", "/home/geir/holiday.png").param("at", "ab12"))
            .andExpect(status().isOk());

        verify(viewFileUseCase).openForView(mid("apalveien5"), "/home/geir/holiday.png", "ab12");
    }

    @Test
    void view_ofAnHtmlFile_isRefused_asABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        // The domain refuses it; the controller must surface that refusal, never fall back to serving the
        // bytes inline under another type. Inline HTML on Vaier's origin runs script against the session.
        when(viewFileUseCase.openForView(any(), any(), any())).thenThrow(new IllegalArgumentException(
            "Vaier will not display \"index.html\" in the browser. Download it instead."));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files/view").param("path", "/srv/www/index.html"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(
                "Vaier will not display \"index.html\" in the browser. Download it instead."));
    }

    @Test
    void view_ofAnUppercaseSvg_isRefusedToo() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(viewFileUseCase.openForView(any(), any(), any())).thenThrow(new IllegalArgumentException(
            "Vaier will not display \"LOGO.SVG\" in the browser. Download it instead."));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/files/view").param("path", "/srv/LOGO.SVG"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_marksWhichEntriesTheBrowserCanDisplay_soTheListingNeedsNoAllowlistOfItsOwn() {
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/home/geir", null)).thenReturn(at("/home/geir",
            FileEntry.in("/home/geir", "holiday.png", false, 4242, WHEN),
            FileEntry.in("/home/geir", "index.html", false, 500, WHEN),
            FileEntry.in("/home/geir", "photos", true, 4096, WHEN)));

        List<FileEntryResponse> entries =
            controller.list(mid("apalveien5").value(), "/home/geir", null).getBody().entries();

        assertThat(entries).filteredOn(e -> e.name().equals("holiday.png")).singleElement()
            .extracting(FileEntryResponse::viewable).isEqualTo(true);
        assertThat(entries).filteredOn(e -> e.name().equals("index.html")).singleElement()
            .extracting(FileEntryResponse::viewable).isEqualTo(false);
        assertThat(entries).filteredOn(e -> e.name().equals("photos")).singleElement()
            .extracting(FileEntryResponse::viewable).isEqualTo(false);
    }

    @Test
    void get_carriesTheDomainsWritableVerdict_perEntryAndForTheFolder_andUnknownStaysUnsaid() {
        // /tmp as geir: the folder takes new entries, but only geir's own may be removed from it.
        FolderAccess access = FolderAccess.of(new FilePermissions(01777, 0, 0), new EffectiveUserIds(1000, Set.of(1000)));
        when(browseFilesUseCase.listDirectory(mid("apalveien5"), "/tmp", null)).thenReturn(new MachineDirectory(
            SftpRoot.NONE, "/tmp", List.of(
                FileEntry.in("/tmp", "mine", false, 1, WHEN, new FilePermissions(0644, 1000, 1000)),
                FileEntry.in("/tmp", "roots", false, 1, WHEN, new FilePermissions(0644, 0, 0)),
                FileEntry.in("/tmp", "unread", false, 1, WHEN)),
            null, ProtectedPaths.none(), access));

        DirectoryResponse body = controller.list(mid("apalveien5").value(), "/tmp", null).getBody();

        assertThat(body.writable()).isTrue();
        assertThat(body.entries()).extracting(FileEntryResponse::writable).containsExactly(true, false, null);
    }

    // --- selection zip: download a whole fleet-wide selection as one zip -------------------------------

    @Test
    void downloadZip_parsesTheSelectionJson_intoCoordinates_andPassesThemToTheUseCase() throws Exception {
        when(downloadFileUseCase.openForDownload(any()))
            .thenReturn(new Download("apalveien5.zip", -1, "application/zip", out -> {
            }));

        String selection = "[{\"machineId\":\"" + mid("apalveien5") + "\",\"machine\":\"apalveien5\",\"path\":\"/home/x\",\"at\":null},"
            + "{\"machineId\":\"" + mid("apalveien5") + "\",\"machine\":\"apalveien5\",\"path\":\"/etc/hosts\",\"at\":\"ab12\"}]";
        controller.downloadZip(selection);

        // The JSON array becomes the selection's coordinates, in order, with `at` carried (null and an id).
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<net.vaier.domain.Selection.Coordinate>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(downloadFileUseCase).openForDownload(captor.capture());
        assertThat(captor.getValue()).containsExactly(
            new Selection.Coordinate(mid("apalveien5"), "apalveien5", "/home/x", null),
            new Selection.Coordinate(mid("apalveien5"), "apalveien5", "/etc/hosts", "ab12"));
    }

    @Test
    void downloadZip_streamsTheZip_asAnAttachment_withTheUseCasesFilename_andNoContentLength() throws Exception {
        byte[] zipBytes = {4, 5, 6};
        when(downloadFileUseCase.openForDownload(any()))
            .thenReturn(new Download("apalveien5.zip", -1, "application/zip", out -> {
                try {
                    out.write(zipBytes);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));

        ResponseEntity<StreamingResponseBody> response =
            controller.downloadZip("[{\"machineId\":\"" + mid("apalveien5") + "\",\"machine\":\"apalveien5\",\"path\":\"/home/x\"}]");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/zip"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"apalveien5.zip\"");
        // A zip's size is not known ahead of time, so no Content-Length is set (mirrors the directory download).
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(zipBytes);
    }

    @Test
    void downloadZip_spanningMachines_carriesTheGenericSelectionFilename() {
        when(downloadFileUseCase.openForDownload(any()))
            .thenReturn(new Download("vaier-selection.zip", -1, "application/zip", out -> {
            }));

        ResponseEntity<StreamingResponseBody> response = controller.downloadZip(
            "[{\"machineId\":\"" + mid("apalveien5") + "\",\"machine\":\"apalveien5\",\"path\":\"/etc\"},{\"machineId\":\"" + mid("colina27") + "\",\"machine\":\"colina27\",\"path\":\"/etc\"}]");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .isEqualTo("attachment; filename=\"vaier-selection.zip\"");
    }

    @Test
    void downloadZip_isPostedAsAFormParameter_soTheBrowserCanStreamItStraightToDisk() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(downloadFileUseCase.openForDownload(any()))
            .thenReturn(new Download("apalveien5.zip", -1, "application/zip", out -> {
            }));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/machines/files/download-zip")
                .param("selection", "[{\"machineId\":\"" + mid("apalveien5") + "\",\"machine\":\"apalveien5\",\"path\":\"/home/x\"}]"))
            .andExpect(status().isOk());

        verify(downloadFileUseCase).openForDownload(any());
    }

    @Test
    void downloadZip_aMalformedSelection_isABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/machines/files/download-zip")
                .param("selection", "not json at all"))
            .andExpect(status().isBadRequest());
    }

    // --- upload: the browser's file into a machine's directory -----------------------------------------

    private static MockMultipartFile part(String filename, String content) {
        return new MockMultipartFile("file", filename, "application/octet-stream",
            content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upload_handsTheUseCaseTheDestination_andAnswersWhereTheFileLanded() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(multipart("/machines/" + mid("apalveien5") + "/files/upload")
                .file(part("notes.txt", "hello"))
                .param("path", "/home/geir"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("notes.txt"))
            .andExpect(jsonPath("$.path").value("/home/geir/notes.txt"));

        // The directory is the query parameter, the filename is the multipart part's own — the controller
        // joins neither; the domain does.
        verify(uploadFileUseCase).upload(
            eq(Upload.into(mid("apalveien5"), "/home/geir", "notes.txt", false)), any());
    }

    @Test
    void upload_withoutAnOverwriteFlag_doesNotAskToReplaceAnything() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(multipart("/machines/" + mid("apalveien5") + "/files/upload")
                .file(part("notes.txt", "hello"))
                .param("path", "/home/geir"))
            .andExpect(status().isOk());

        ArgumentCaptor<Upload> upload = ArgumentCaptor.forClass(Upload.class);
        verify(uploadFileUseCase).upload(upload.capture(), any());
        assertThat(upload.getValue().overwrite()).isFalse();
    }

    @Test
    void upload_withOverwriteAsked_carriesItThrough() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(multipart("/machines/" + mid("apalveien5") + "/files/upload")
                .file(part("notes.txt", "hello"))
                .param("path", "/home/geir")
                .param("overwrite", "true"))
            .andExpect(status().isOk());

        ArgumentCaptor<Upload> upload = ArgumentCaptor.forClass(Upload.class);
        verify(uploadFileUseCase).upload(upload.capture(), any());
        assertThat(upload.getValue().overwrite()).isTrue();
    }

    @Test
    void upload_ontoANameAlreadyTaken_isAConflict_carryingTheDomainsOwnSentence() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new ConflictException("\"notes.txt\" is already in /home/geir."))
            .when(uploadFileUseCase).upload(any(), any());

        // 409 is what turns the Explorer's silent write into the "Replace it?" question — the operator is
        // asked, never overruled.
        mockMvc.perform(multipart("/machines/" + mid("apalveien5") + "/files/upload")
                .file(part("notes.txt", "hello"))
                .param("path", "/home/geir"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("\"notes.txt\" is already in /home/geir."));
    }

    @Test
    void upload_ofAPartWithNoFilename_isABadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();

        mockMvc.perform(multipart("/machines/" + mid("apalveien5") + "/files/upload")
                .file(new MockMultipartFile("file", "", "application/octet-stream", "x".getBytes(UTF_8)))
                .param("path", "/home/geir"))
            .andExpect(status().isBadRequest());

        verify(uploadFileUseCase, never()).upload(any(), any());
    }

    /**
     * The whole point of the endpoint: a multi-gigabyte file must not become a multi-gigabyte byte array in
     * Vaier's heap on its way to the machine. The part is read as a stream and never as bytes — so this pins
     * {@code getInputStream()} and forbids {@code getBytes()}, which is the one line that would silently turn
     * a flat-memory path into an OOM.
     */
    @Test
    void upload_streamsThePart_andNeverReadsItWholeIntoMemory() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        InputStream content = new ByteArrayInputStream("hello".getBytes(UTF_8));
        when(file.getOriginalFilename()).thenReturn("notes.txt");
        when(file.getInputStream()).thenReturn(content);

        controller.upload(mid("apalveien5").value(), "/home/geir", false, file);

        verify(uploadFileUseCase).upload(any(), eq(content));
        verify(file, never()).getBytes();
    }

    // --- slice 5: delete (present-only, destructive) ---------------------------------------------------

    @Test
    void delete_removesThePath_andAnswers204NoContent() {
        ResponseEntity<Void> response = controller.delete(mid("apalveien5").value(), "/home/geir/old");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(deleteFileUseCase).delete(mid("apalveien5"), "/home/geir/old");
    }

    @Test
    void delete_passesThePathThroughVerbatim_asTheQueryParameter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(delete("/machines/" + mid("apalveien5") + "/files").param("path", "/var/tmp/junk"))
            .andExpect(status().isNoContent());

        // The path is handed to the domain verbatim — the controller does not sanitise it, the domain does.
        verify(deleteFileUseCase).delete(mid("apalveien5"), "/var/tmp/junk");
    }

    @Test
    void delete_ofTheSftpRoot_isABadRequest_carryingTheGuardSentence() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new CannotDeleteSftpRootException("/volume1")).when(deleteFileUseCase).delete(mid("NAS"), "/volume1");

        mockMvc.perform(delete("/machines/" + mid("NAS") + "/files").param("path", "/volume1"))
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
            .when(deleteFileUseCase).delete(mid("apalveien5"), "/home/geir/ghost");

        mockMvc.perform(delete("/machines/" + mid("apalveien5") + "/files").param("path", "/home/geir/ghost"))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_thatTheSshUserMayNotPerform_isForbidden() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        doThrow(new PermissionDeniedException("Not allowed to read /root as geir."))
            .when(deleteFileUseCase).delete(mid("apalveien5"), "/root");

        mockMvc.perform(delete("/machines/" + mid("apalveien5") + "/files").param("path", "/root"))
            .andExpect(status().isForbidden());
    }

    @Test
    void archives_listsTheMachinesArchives_newestFirst_withIdNameAndTime() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(listMachineArchivesUseCase.listMachineArchives(mid("apalveien5"))).thenReturn(List.of(
            new Archive("apalveien5-2026-07-14T02:00:00", "b", Instant.parse("2026-07-14T02:00:00Z")),
            new Archive("apalveien5-2026-07-13T02:00:00", "a", Instant.parse("2026-07-13T02:00:00Z"))));

        mockMvc.perform(get("/machines/" + mid("apalveien5") + "/archives"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("b"))
            .andExpect(jsonPath("$[0].name").value("apalveien5-2026-07-14T02:00:00"))
            .andExpect(jsonPath("$[0].createdAt").value("2026-07-14T02:00:00Z"))
            .andExpect(jsonPath("$[1].id").value("a"));
    }
}
