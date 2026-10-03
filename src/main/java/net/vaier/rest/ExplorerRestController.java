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
import net.vaier.application.UploadFileUseCase;
import net.vaier.application.ViewFileUseCase;
import net.vaier.application.ViewFileUseCase.View;
import net.vaier.domain.Archive;
import net.vaier.domain.FileEntry;
import net.vaier.domain.FolderAccess;
import net.vaier.domain.MachineId;
import net.vaier.domain.Selection;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.Upload;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
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
    private final ViewFileUseCase viewFileUseCase;
    private final UploadFileUseCase uploadFileUseCase;
    private final ObjectMapper objectMapper;

    /**
     * Browse one directory on one machine. With {@code at} naming an archive, the same directory is read
     * <em>inside that archive</em> — the machine's past. Absent {@code at}, the live filesystem, unchanged
     * (#326: omitting {@code path} is still a question, not a default).
     */
    @GetMapping("/machines/{machineId}/files")
    public ResponseEntity<DirectoryResponse> list(@PathVariable String machineId,
                                                  @RequestParam(required = false) String path,
                                                  @RequestParam(required = false) String at) {
        log.debug("Browsing {} on machine {} at archive {}",
            LogSafe.forLog(path), LogSafe.forLog(machineId), LogSafe.forLog(at));
        MachineDirectory directory = browseFilesUseCase.listDirectory(MachineId.of(machineId), path, at);
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
    @GetMapping("/machines/{machineId}/files/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String machineId,
                                                          @RequestParam String path,
                                                          @RequestParam(required = false) String at,
                                                          @RequestParam(required = false) String name) {
        log.info("Downloading {} from machine {} at archive {}",
            LogSafe.forLog(path), LogSafe.forLog(machineId), LogSafe.forLog(at));
        // `name` is what to call the machine in the filename when the download is a whole root, which
        // has no basename of its own. Optional: a missing one costs a nicer filename, never the file.
        Download download = downloadFileUseCase.openForDownload(
            MachineId.of(machineId), name == null || name.isBlank() ? "files" : name, path, at);
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
     * Open one file from a machine <b>in the browser</b> — the Explorer's Open verb. Deliberately a second
     * endpoint rather than a mode of {@code /files/download}: download always means "save this", and turning
     * it into "opens in a tab for some file types" would change a verb the operator already relies on.
     *
     * <p>Everything that makes this safe is the domain's ({@link net.vaier.domain.ViewableFile}), because
     * serving fleet bytes inline happens on <em>Vaier's own origin</em> with the operator's session in reach.
     * The use case answers only for a viewable file — a request for anything else is an
     * {@code IllegalArgumentException}, a {@code 400} via {@link GlobalExceptionHandler}, never a fallback to
     * serving the bytes inline under some other type. What the controller adds is only the wiring:
     * {@code inline} rather than {@code attachment}, {@code nosniff} so the declared type is the only type the
     * browser will consider, and the {@code Content-Security-Policy} the domain chose for that media type.
     *
     * <p>{@code at} may name an archive: a view is a read, so opening a file as it was in the past works
     * exactly as downloading it does.
     */
    @GetMapping("/machines/{machineId}/files/view")
    public ResponseEntity<StreamingResponseBody> view(@PathVariable String machineId,
                                                      @RequestParam String path,
                                                      @RequestParam(required = false) String at) {
        log.info("Opening {} from machine {} at archive {}",
            LogSafe.forLog(path), LogSafe.forLog(machineId), LogSafe.forLog(at));
        View view = viewFileUseCase.openForView(MachineId.of(machineId), path, at);
        StreamingResponseBody body = view.writer()::accept;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + sanitiseFilename(view.filename()) + "\"")
            .header(CONTENT_TYPE_OPTIONS, "nosniff")
            .header(CONTENT_SECURITY_POLICY, view.contentSecurityPolicy())
            .contentType(MediaType.parseMediaType(view.mediaType()));
        if (view.sizeBytes() >= 0) {
            response = response.contentLength(view.sizeBytes());
        }
        return response.body(body);
    }

    /** "Treat the declared type as the only type" — no MIME sniffing on a response Vaier serves inline. */
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** The policy an inline response is served under; what it says is the domain's decision, not this one. */
    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";

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
            .map(item -> new Selection.Coordinate(
                MachineId.of(item.machineId()), item.machine(), item.path(), item.at()))
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
    @DeleteMapping("/machines/{machineId}/files")
    public ResponseEntity<Void> delete(@PathVariable String machineId, @RequestParam String path) {
        log.info("Deleting {} on machine {}", LogSafe.forLog(path), LogSafe.forLog(machineId));
        deleteFileUseCase.delete(MachineId.of(machineId), path);
        return ResponseEntity.noContent().build();
    }

    /**
     * Put one file from the operator's browser into a directory on a machine — the mirror of
     * {@code /files/download} (#321). Multipart, one file per request: the browser sends them one at a time so
     * each gets its own progress and its own answer, which is what makes a per-file "Replace it?" possible.
     *
     * <p>The bytes are <b>streamed</b>, never buffered: {@link MultipartFile#getInputStream()} goes straight to
     * the use case, so a multi-gigabyte file costs the same memory as a small one. There is no {@code at} —
     * a machine's past is a read-only archive, so an upload only ever writes the live filesystem.
     *
     * <p>The controller joins nothing and validates nothing. The destination directory is the {@code path}
     * parameter and the name is the part's own, both handed to {@link Upload} verbatim — it decides where the
     * file lands and whether the name may be taken. A name already in use is a {@code 409} carrying the
     * domain's own sentence (via {@link GlobalExceptionHandler}), which is what the Explorer turns into the
     * "Replace it?" question before retrying with {@code overwrite}. Never a silent replacement.
     */
    @PostMapping("/machines/{machineId}/files/upload")
    public ResponseEntity<UploadResponse> upload(@PathVariable String machineId,
                                                 @RequestParam String path,
                                                 @RequestParam(defaultValue = "false") boolean overwrite,
                                                 @RequestParam("file") MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        log.info("Uploading {} into {} on machine {}",
            LogSafe.forLog(filename), LogSafe.forLog(path), LogSafe.forLog(machineId));
        Upload upload = Upload.into(MachineId.of(machineId), path, filename, overwrite);
        uploadFileUseCase.upload(upload, file.getInputStream());
        return ResponseEntity.ok(new UploadResponse(upload.filename(), upload.destinationPath()));
    }

    /**
     * The archives this machine can be browsed at, newest first — the time rail's data. Each carries the
     * {@code id} the browser hands back as the {@code at} coordinate, plus a display name and creation time.
     */
    @GetMapping("/machines/{machineId}/archives")
    public ResponseEntity<List<ArchiveResponse>> archives(@PathVariable String machineId) {
        log.debug("Listing archives for machine {}", LogSafe.forLog(machineId));
        return ResponseEntity.ok(
            listMachineArchivesUseCase.listMachineArchives(MachineId.of(machineId)).stream()
            .map(ArchiveResponse::from).toList());
    }

    /**
     * Where an uploaded file landed: the {@code name} it was stored under, and its absolute {@code path} —
     * the machine's own true coordinate, so the browser can name what it just wrote without reassembling it.
     */
    record UploadResponse(String name, String path) {
    }

    /**
     * One picked coordinate in a {@code download-zip} selection: the {@code machine}, the {@code path} (the
     * machine's own true coordinate, handed to the domain verbatim), and an optional {@code at} — {@code null}
     * or absent for the live filesystem, or an archive id for the past. Jackson binds each element of the
     * {@code selection} JSON array to one of these.
     */
    record SelectionItem(String machineId, String machine, String path, String at) {
    }

    /**
     * One directory on one machine: where the machine's file tree begins ({@code root}), which directory
     * these entries were read from ({@code path}), and the entries themselves.
     *
     * <p>The root travels with every listing because the browser cannot deduce it and must not assume it. A
     * bare array — what this endpoint answered with before #326 — had nowhere to carry it, and a browser that
     * assumed {@code /} opened the NAS on the one path the NAS cannot answer.
     *
     * <p>{@code writable} says whether the sign-in may add to this folder ({@link FolderAccess#toAdd}) — Upload
     * and Paste — with {@code null} for not known.
     */
    record DirectoryResponse(String root, String path, String at, Boolean writable,
                             List<FileEntryResponse> entries) {
        static DirectoryResponse from(MachineDirectory directory) {
            // Whether an entry is backed up (or merely contains backed-up content) is the domain's decision —
            // ProtectedPaths.covers / enclosesUnder on what the machine actually backs up, source paths minus
            // excludes — asked here per entry so the browser only has to render the flags. In the past the
            // protection is empty, so every archived entry is simply unmarked.
            ProtectedPaths protectedPaths = directory.protectedPaths();
            FolderAccess access = directory.access();
            return new DirectoryResponse(directory.root().path(), directory.path(), directory.at(),
                access.toAdd().answer(),
                directory.entries().stream()
                    .map(entry -> FileEntryResponse.from(entry, protectedPaths, access))
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
     * {@link ProtectedPaths#isBackedUp} / {@link ProtectedPaths#containsBackedUp} — so the Explorer can render
     * a full or half shield without re-implementing the containment rule in JS. {@code backedUp} is true when
     * a job protects this exact path or an ancestor of it, no exclude carves it back out, <em>and</em> no
     * exclude carves a hole anywhere inside it — a full shield promises the whole folder is in the archive.
     * {@code containsBackedUp} is the half shield: backed-up content lives inside, but the entry is not whole.
     * The two are mutually exclusive. Both are always {@code false} for an archived (past) listing.
     *
     * <p>{@code viewable} is the server's verdict — via {@link FileEntry#viewable()} — on whether Vaier will
     * hand this entry to the browser to display, so the Explorer can render its name as a link without holding
     * a copy of the allowlist. The browser must not hold one: the allowlist is a security boundary, and a
     * second copy of a security boundary is a copy that drifts. Unaffected by the past — opening a file as it
     * was in an archive is a read like any other.
     *
     * <p>{@code writable} is the domain's verdict ({@link FolderAccess#toRemove}) on whether Vaier's sign-in may
     * remove this entry: {@code false} withholds Delete, and {@code null} — not known — keeps it offered.
     */
    record FileEntryResponse(String name, String path, boolean directory, long size, String modifiedAt,
                             boolean backedUp, boolean containsBackedUp, boolean viewable, Boolean writable) {
        static FileEntryResponse from(FileEntry entry, ProtectedPaths protectedPaths, FolderAccess access) {
            // Both verdicts are asked of the domain whole — including their mutual exclusion. Restating that
            // rule here with a !backedUp guard is how the two copies eventually disagree.
            boolean backedUp = protectedPaths.isBackedUp(entry.path());
            boolean containsBackedUp = protectedPaths.containsBackedUp(entry.path());
            return new FileEntryResponse(entry.name(), entry.path(), entry.directory(),
                entry.sizeBytes(), entry.modified().toString(), backedUp, containsBackedUp, entry.viewable(),
                access.toRemove(entry).answer());
        }
    }
}
