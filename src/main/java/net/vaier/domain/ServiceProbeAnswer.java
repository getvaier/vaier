package net.vaier.domain;

/**
 * What a published service's backend answered to one plain GET, as Vaier asked it directly rather than through
 * Traefik: the status, its challenge and redirect target if any, and the start of its body.
 */
public record ServiceProbeAnswer(int status, String wwwAuthenticate, String location, String contentType,
                                 String body) {
}
