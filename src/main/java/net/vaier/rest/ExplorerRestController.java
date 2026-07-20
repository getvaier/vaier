package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.application.DeleteFileUseCase;
import net.vaier.application.DownloadFileUseCase;
import net.vaier.application.DownloadFileUseCase.Download;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.ListMachineArchivesUseCase;
import net.vaier.domain.Archive;
import net.vaier.domain.FileEntry;
import net.vaier.domain.Selection;
import net.vaier.domain.SourcePaths;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * The Explorer's read side (#321, slice 1): one directory listing on one machine. This path is
 * non-whitelisted, so it sits under the admin auth chain like every other machine endpoint — browsing
 * the fleet's filesystems is never anonymous.
 *
 * <p>The {@code path} query parameter comes straight from the browser and is handed to the domain
 * verbatim: the controller does not sanitise it, {@link FileEntry#normalisePath} does. A path that is
 * not absolute, or that climbs above the root, throws {@code IllegalArgumentException} and surfaces as
 * a {@code 400} via {@link GlobalExceptionHandler} — never as a connection to a machine.
 *
 * <p><b>Omitting {@code path} is a question, not a default</b> (#326). It means "wherever this machine's file
 * tree begins", and only the machine can answer that: an SFTP subsystem chrooted into {@code /volume1} cannot
 * be asked about {@code /} at all. So no default is filled in here — the missing path travels to the domain as
 * {@code null} and comes back resolved, with the root that resolved it.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ExplorerRestController {

    private final BrowseFilesUseCase browseFilesUseCase;
    private final ListMachineArchivesUseCase listMachineArchivesUseCase;
    private final DownloadFileUseCase downloadFileUseCase;
    private final DeleteFileUseCase deleteFileUseCase;
    private final ObjectMapper objectMapper;

    /**
     * Browse one directory on one machine. With {@code at} naming an archive, the same directory is read
     * <em>inside that archive</em> — the machine's past. Absent {@code at}, the live filesystem, unchanged
     * (#326: omitting {@code path} is still a question, not a default).
     */
    @GetMapping("/machines/{machine}/files")
    public ResponseEntity<DirectoryResponse> list(@PathVariable String machine,
                                                  @RequestParam(required = false) String path,
                                                  @RequestParam(required = false) String at) {
        log.debug("Browsing {} on machine {} at archive {}",
            LogSafe.forLog(path), LogSafe.forLog(machine), LogSafe.forLog(at));
        MachineDirectory directory = browseFilesUseCase.listDirectory(machine, path, at);
        return ResponseEntity.ok(DirectoryResponse.from(directory));
    }

    /**
     * Download one file or directory from a machine — the Explorer's "the browser is a download"
     * destination (#321, slice 2). The bytes are streamed straight through Vaier from the machine's SFTP
     * service, so memory stays flat regardless of size. {@code at} may name an archive: a download is a
     * read, so the past is fine — zipping it included. A file streams as-is; a directory streams as a zip of
     * its whole tree, built by the use case as it walks. A zip's size is not known ahead of time, so
     * {@code Content-Length} is only set when the use case reports one (a file always does; a directory
     * never does — {@link Download#sizeBytes()} is {@code -1}).
     */
    @GetMapping("/machines/{machine}/files/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String machine,
                                                          @RequestParam String path,
                                                          @RequestParam(required = false) String at) {
        log.info("Downloading {} from machine {} at archive {}",
            LogSafe.forLog(path), LogSafe.forLog(machine), LogSafe.forLog(at));
        Download download = downloadFileUseCase.openForDownload(machine, path, at);
        StreamingResponseBody body = download.writer()::accept;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + sanitiseFilename(download.filename()) + "\"")
            .contentType(MediaType.parseMediaType(download.contentType()));
        if (download.sizeBytes() >= 0) {
            response = response.contentLength(download.sizeBytes());
        }
        return response.body(body);
    }

    /**
     * Download a whole fleet-wide selection as one zip — the Explorer selection bar's "download everything"
     * (#321). The {@code selection} is a JSON array of coordinates ({@code machine}, {@code path}, and an
     * optional {@code at} naming an archive), each a file or directory; the use case resolves, stats and
     * streams them all into one {@code application/zip}. It is a <b>POST form parameter</b>, not a JSON body,
     * because the browser triggers the download by submitting a hidden form, which streams the zip straight
     * to disk with no in-browser buffering. As with a single directory download, a zip's size is not known
     * ahead of time, so no {@code Content-Length} is set. A malformed selection is a {@code 400}.
     */
    @PostMapping("/machines/files/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@RequestParam("selection") String selection) {
        List<Selection.Coordinate> coordinates = parseSelection(selection);
        log.info("Downloading a {}-coordinate selection as one zip", coordinates.size());
        Download download = downloadFileUseCase.openForDownload(coordinates);
        StreamingResponseBody body = download.writer()::accept;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + sanitiseFilename(download.filename()) + "\"")
            .contentType(MediaType.parseMediaType(download.contentType()));
        if (download.sizeBytes() >= 0) {
            response = response.contentLength(download.sizeBytes());
        }
        return response.body(body);
    }

    /**
     * Parse the {@code selection} JSON array into domain coordinates. The strings come straight from the
     * browser, so a malformed array is a {@code 400} (an {@link IllegalArgumentException} via
     * {@link GlobalExceptionHandler}), never a {@code 500}. Each path stays verbatim — the domain, not the
     * controller, decides what a browsable path is.
     */
    private List<Selection.Coordinate> parseSelection(String selection) {
        SelectionItem[] items;
        try {
            items = objectMapper.readValue(selection, SelectionItem[].class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed selection: expected a JSON array of coordinates");
        }
        return java.util.Arrays.stream(items)
            .map(item -> new Selection.Coordinate(item.machine(), item.path(), item.at()))
            .toList();
    }

    /**
     * A filename safe to place inside a {@code Content-Disposition} header: no quotes, backslashes or CR/LF,
     * so a crafted filename cannot break out of the quoted value or inject a header.
     */
    private static String sanitiseFilename(String filename) {
        return filename.replaceAll("[\"\\\\\r\n]", "_");
    }

    /**
     * Delete a file or directory on a machine — the Explorer's present-only, destructive mutate (#321, slice
     * 5). A directory is deleted recursively. There is <b>no</b> {@code at}: you cannot delete the past — an
     * archive is read-only by construction — so a delete only ever touches the live filesystem. The frontend
     * gates this behind a typed machine-name confirmation; the backend deletes safely and reports clearly.
     *
     * <p>On success the response is {@code 204 No Content}. A path that is not there is a {@code 404}, a
     * permission-denied is a {@code 403}, and the SFTP-root guard — you cannot delete a machine's whole
     * browsable tree — is a {@code 400} carrying its own sentence (all via {@link GlobalExceptionHandler}).
     */
    @DeleteMapping("/machines/{machine}/files")
    public ResponseEntity<Void> delete(@PathVariable String machine, @RequestParam String path) {
        log.info("Deleting {} on machine {}", LogSafe.forLog(path), LogSafe.forLog(machine));
        deleteFileUseCase.delete(machine, path);
        return ResponseEntity.noContent().build();
    }

    /**
     * The archives this machine can be browsed at, newest first — the time rail's data. Each carries the
     * {@code id} the browser hands back as the {@code at} coordinate, plus a display name and creation time.
     */
    @GetMapping("/machines/{machine}/archives")
    public ResponseEntity<List<ArchiveResponse>> archives(@PathVariable String machine) {
        log.debug("Listing archives for machine {}", LogSafe.forLog(machine));
        return ResponseEntity.ok(listMachineArchivesUseCase.listMachineArchives(machine).stream()
            .map(ArchiveResponse::from).toList());
    }

    /**
     * One picked coordinate in a {@code download-zip} selection: the {@code machine}, the {@code path} (the
     * machine's own true coordinate, handed to the domain verbatim), and an optional {@code at} — {@code null}
     * or absent for the live filesystem, or an archive id for the past. Jackson binds each element of the
     * {@code selection} JSON array to one of these.
     */
    record SelectionItem(String machine, String path, String at) {
    }

    /**
     * One directory on one machine: where the machine's file tree begins ({@code root}), which directory
     * these entries were read from ({@code path}), and the entries themselves.
     *
     * <p>The root travels with every listing because the browser cannot deduce it and must not assume it. A
     * bare array — what this endpoint answered with before #326 — had nowhere to carry it, and a browser that
     * assumed {@code /} opened the NAS on the one path the NAS cannot answer.
     */
    record DirectoryResponse(String root, String path, String at, List<FileEntryResponse> entries) {
        static DirectoryResponse from(MachineDirectory directory) {
            // Whether an entry is backed up (or merely contains backed-up content) is the domain's decision —
            // SourcePaths.covers / enclosesUnder on the machine's protected paths — asked here per entry so the
            // browser only has to render the flags. In the past the protected set is empty, so every archived
            // entry is simply unmarked.
            SourcePaths protectedPaths = directory.protectedPaths();
            return new DirectoryResponse(directory.root().path(), directory.path(), directory.at(),
                directory.entries().stream()
                    .map(entry -> FileEntryResponse.from(entry, protectedPaths))
                    .toList());
        }
    }

    /**
     * One archive on the machine's time rail: the borg {@code id} the browser sends back as the {@code at}
     * coordinate to browse the past, a display {@code name}, and the {@code createdAt} time (ISO-8601, or
     * {@code null} when borg reported no readable time) that places it on the rail.
     */
    record ArchiveResponse(String name, String id, String createdAt) {
        static ArchiveResponse from(Archive archive) {
            return new ArchiveResponse(archive.name(), archive.id(),
                archive.time() == null ? null : archive.time().toString());
        }
    }

    /**
     * One file or directory in the listing. Each entry carries its own absolute path — the machine's <b>true</b>
     * path, the one {@code df}, borg and the operator's own terminal use — so the browser can descend into a
     * directory without reassembling paths itself, and never has to guess how Vaier normalised the one it
     * asked for.
     *
     * <p>{@code backedUp} and {@code containsBackedUp} are the server's verdict — via the domain
     * {@link net.vaier.domain.SourcePaths#covers} / {@link net.vaier.domain.SourcePaths#enclosesUnder} — so the
     * Explorer can render a full or half shield without re-implementing the containment rule in JS. {@code
     * backedUp} is true when a job protects this exact path or an ancestor of it; {@code containsBackedUp} is
     * true only when the entry is <em>not</em> itself backed up but a source path lives somewhere inside it
     * (the two are mutually exclusive). Both are always {@code false} for an archived (past) listing.
     */
    record FileEntryResponse(String name, String path, boolean directory, long size, String modifiedAt,
                             boolean backedUp, boolean containsBackedUp) {
        static FileEntryResponse from(FileEntry entry, SourcePaths protectedPaths) {
            boolean backedUp = protectedPaths.covers(entry.path());
            boolean containsBackedUp = !backedUp && protectedPaths.enclosesUnder(entry.path());
            return new FileEntryResponse(entry.name(), entry.path(), entry.directory(),
                entry.sizeBytes(), entry.modified().toString(), backedUp, containsBackedUp);
        }
    }
}
