package net.vaier.application;

import net.vaier.domain.Selection;

import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Open a file or directory on a machine for download to the browser — the Explorer's "the browser is a
 * download" destination (#321, slice 2; directory-as-zip in the slice 2 follow-up). Resolves the
 * coordinate, stats it, and hands back a streamable handle: the download's filename, its size (when known),
 * its content type, and a writer that pipes the bytes to an {@link OutputStream} only when invoked — so
 * memory stays flat and the SFTP read(s) are opened lazily, at streaming time.
 *
 * <p>A file streams as-is. A directory streams as a {@code application/zip} of its whole tree, built by
 * walking it and streaming each file straight into the zip — never buffering the archive whole.
 */
public interface DownloadFileUseCase {

    /**
     * Prepare the file or directory at {@code path} on {@code machineName} at time {@code at} for download.
     * A download is a read, so the past is allowed ({@code at} may name an archive) — including zipping a
     * directory as it was in that archive.
     *
     * @param path the file or directory to download, at the machine's own true coordinates — required
     * @throws IllegalArgumentException when {@code path} is not absolute
     * @throws net.vaier.domain.NotFoundException when the machine, or the path, is not there
     */
    Download openForDownload(String machineName, String path, String at);

    /**
     * Prepare a whole fleet-wide {@link Selection} of coordinates for download as one {@code application/zip}
     * — the "download the whole selection as one zip" destination for the Explorer's selection bar. The
     * selection may span machines and points in time; each coordinate is resolved, stat'd and streamed into
     * one shared zip (a file as one entry, a directory as its whole walked subtree). The arrangement — the
     * filename, the per-coordinate entry names, machine-prefixing and collision de-duping — is the
     * {@link Selection}'s decision; this only orchestrates the streaming.
     *
     * <p>A coordinate whose file the SSH user cannot read, or that has vanished, is skipped — never a
     * half-written entry — exactly as a single directory walk skips an unreadable file.
     *
     * @param coordinates the picked files and directories, in selection order — at least one
     */
    Download openForDownload(List<Selection.Coordinate> coordinates);

    /**
     * Something ready to stream: its {@code filename} (the basename for a file, or {@code <dirname>.zip}
     * for a directory — for {@code Content-Disposition}), its {@code sizeBytes} (for {@code Content-Length};
     * {@code -1} when it cannot be known ahead of time — a directory is zipped on the fly, and a zip's byte
     * count is not the sum of the files it holds), its {@code contentType} (a MIME type, e.g.
     * {@code application/octet-stream} or {@code application/zip}), and a {@code writer} that streams the
     * bytes to a given {@link OutputStream} when called — opening the SFTP read(s) only then.
     */
    record Download(String filename, long sizeBytes, String contentType, Consumer<OutputStream> writer) {
    }
}
