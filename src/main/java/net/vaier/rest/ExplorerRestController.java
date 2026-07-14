package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
import net.vaier.application.BrowseFilesUseCase.MachineDirectory;
import net.vaier.domain.FileEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/machines/{machine}/files")
    public ResponseEntity<DirectoryResponse> list(@PathVariable String machine,
                                                  @RequestParam(required = false) String path) {
        log.debug("Browsing {} on machine {}", LogSafe.forLog(path), LogSafe.forLog(machine));
        MachineDirectory directory = browseFilesUseCase.listDirectory(machine, path);
        return ResponseEntity.ok(DirectoryResponse.from(directory));
    }

    /**
     * One directory on one machine: where the machine's file tree begins ({@code root}), which directory
     * these entries were read from ({@code path}), and the entries themselves.
     *
     * <p>The root travels with every listing because the browser cannot deduce it and must not assume it. A
     * bare array — what this endpoint answered with before #326 — had nowhere to carry it, and a browser that
     * assumed {@code /} opened the NAS on the one path the NAS cannot answer.
     */
    record DirectoryResponse(String root, String path, List<FileEntryResponse> entries) {
        static DirectoryResponse from(MachineDirectory directory) {
            return new DirectoryResponse(directory.root().path(), directory.path(),
                directory.entries().stream().map(FileEntryResponse::from).toList());
        }
    }

    /**
     * One file or directory in the listing. Each entry carries its own absolute path — the machine's <b>true</b>
     * path, the one {@code df}, borg and the operator's own terminal use — so the browser can descend into a
     * directory without reassembling paths itself, and never has to guess how Vaier normalised the one it
     * asked for.
     */
    record FileEntryResponse(String name, String path, boolean directory, long size, String modifiedAt) {
        static FileEntryResponse from(FileEntry entry) {
            return new FileEntryResponse(entry.name(), entry.path(), entry.directory(),
                entry.sizeBytes(), entry.modified().toString());
        }
    }
}
