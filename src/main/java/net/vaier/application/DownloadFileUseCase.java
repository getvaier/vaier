package net.vaier.application;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Open a file on a machine for download to the browser — the Explorer's "the browser is a download"
 * destination (#321, slice 2). Resolves the coordinate, refuses a directory (a folder download is not yet
 * supported), and hands back a streamable handle: the download's filename, its size, and a writer that
 * pipes the bytes to an {@link OutputStream} only when invoked — so memory stays flat and the SFTP read is
 * opened lazily, at streaming time.
 */
public interface DownloadFileUseCase {

    /**
     * Prepare the file at {@code path} on {@code machineName} at time {@code at} for download. A download is
     * a read, so the past is allowed ({@code at} may name an archive). A directory is refused up front, before
     * any bytes or headers, so the browser gets a clean 400 rather than a broken stream.
     *
     * @param path the file to download, at the machine's own true coordinates — required
     * @throws IllegalArgumentException when {@code path} is not absolute, or names a directory
     * @throws net.vaier.domain.NotFoundException when the machine, or the file, is not there
     */
    Download openForDownload(String machineName, String path, String at);

    /**
     * A file ready to stream: its {@code filename} (the basename, for {@code Content-Disposition}), its
     * {@code sizeBytes} (for {@code Content-Length}), and a {@code writer} that streams the bytes to a given
     * {@link OutputStream} when called — opening the SFTP read only then.
     */
    record Download(String filename, long sizeBytes, Consumer<OutputStream> writer) {
    }
}
