package net.vaier.application;

import java.util.Optional;

public interface GetIconUseCase {

    /**
     * Resolve the icon for a published service identified by {@code host} (and optional
     * {@code pathPrefix}, used for path-routed services that share a hostname). Tries: the
     * site's own {@code <link rel="icon">} hint, well-known fallback paths
     * ({@code /favicon.ico}, {@code /apple-touch-icon.png}, …), then external icon CDNs.
     * Result includes the bytes and the deduced content-type the controller should report.
     */
    Optional<Icon> getIcon(String host, String pathPrefix);

    /**
     * @param body        the icon's bytes.
     * @param contentType the MIME type the bytes represent, deduced from the magic bytes rather
     *                    than the upstream server's header (CDN responses are often mis-typed).
     */
    record Icon(byte[] body, String contentType) {}
}
