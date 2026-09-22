package net.vaier.domain.port;

import net.vaier.domain.ConsoleCertificate;

import java.util.Optional;

/**
 * Driven port: read the certificate the world is shown for one of Vaier's own hosts, off a live TLS handshake.
 * Empty when the front door could not be reached at all; the adapter never throws.
 */
public interface ForInspectingConsoleCertificates {

    Optional<ConsoleCertificate> inspect(String host);
}
