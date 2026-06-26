package net.vaier.domain;

/**
 * A resolved service icon: the raw image bytes plus the MIME type the controller should report.
 *
 * @param body        the icon's bytes.
 * @param contentType the MIME type the bytes represent, deduced from the magic bytes rather than
 *                    the upstream server's header (CDN responses are often mis-typed).
 */
public record Icon(byte[] body, String contentType) {}
