package net.vaier.application;

import java.io.OutputStream;
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
