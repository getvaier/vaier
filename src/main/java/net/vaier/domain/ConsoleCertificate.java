package net.vaier.domain;

import java.time.Instant;

/**
 * The certificate the world is shown for one of Vaier's own hosts, read off a live TLS handshake: who issued it,
 * when it stops being valid, and whether it is self-signed — which is what Traefik presents until Let's Encrypt
 * has issued a real one.
 */
public record ConsoleCertificate(String issuer, Instant notAfter, boolean selfSigned) {

    /** Whether this is the self-signed placeholder Traefik serves before any certificate was issued. */
    public boolean isPlaceholder() {
        return selfSigned;
    }
}
