package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BrowseFilesUseCase;
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
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ExplorerRestController {

    private final BrowseFilesUseCase browseFilesUseCase;

    @GetMapping("/machines/{machine}/files")
    public ResponseEntity<List<FileEntryResponse>> list(@PathVariable String machine,
                                                        @RequestParam(defaultValue = "/") String path) {
        log.debug("Browsing {} on machine {}", LogSafe.forLog(path), LogSafe.forLog(machine));
        List<FileEntry> entries = browseFilesUseCase.listDirectory(machine, path);
        return ResponseEntity.ok(entries.stream().map(FileEntryResponse::from).toList());
    }

    /**
     * One file or directory in the listing. Each entry carries its own absolute path, so the browser can
     * descend into a directory without reassembling paths itself — and never has to guess how Vaier
     * normalised the one it asked for.
     */
    record FileEntryResponse(String name, String path, boolean directory, long size, String modifiedAt) {
        static FileEntryResponse from(FileEntry entry) {
            return new FileEntryResponse(entry.name(), entry.path(), entry.directory(),
                entry.sizeBytes(), entry.modified().toString());
        }
    }
}
