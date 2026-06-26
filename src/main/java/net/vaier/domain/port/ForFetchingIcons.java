package net.vaier.domain.port;

import java.util.Optional;

/**
 * Driven port for the I/O side of icon resolution: fetching HTML pages and image bytes from
 * remote servers. The application service uses this port plus the pure rules in
 * {@link net.vaier.domain.IconResolution} to assemble a full icon lookup.
 */
public interface ForFetchingIcons {

    /** Fetch a page's HTML body. Empty on any non-200, network error, or timeout. */
    Optional<String> fetchHtml(String url);

    /** Fetch a URL's body bytes together with the server-reported content-type header. */
    Optional<FetchedBytes> fetchBytes(String url);

    /**
     * @param body        the response body.
     * @param contentType the {@code Content-Type} response header; may be {@code null} or generic
     *                    (e.g. {@code application/octet-stream}) — callers fall back to magic-byte
     *                    detection in that case.
     */
    record FetchedBytes(byte[] body, String contentType) {}
}
