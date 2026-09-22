package net.vaier.application;

import net.vaier.domain.ConsoleCertificate;

import java.util.Optional;

/**
 * The certificate the world is shown for one of Vaier's own hosts, for the pre-flight's "is it working?" (#265).
 * Empty when the front door could not be reached; never throws.
 */
public interface InspectConsoleCertificateUseCase {

    Optional<ConsoleCertificate> inspectConsoleCertificate(String host);
}
