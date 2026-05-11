package net.vaier.application;

import net.vaier.domain.LanAnchor;

import java.util.Optional;

public interface ResolveLanAnchorUseCase {

    /**
     * Resolves what routes packets to {@code lanAddress}: a relay peer whose {@code lanCidr}
     * contains it, or the Vaier server itself when the address is inside the server LAN CIDR
     * (see {@code domain.LanAnchor}). Empty when neither covers it — i.e. the address can't be
     * registered as a LAN server.
     *
     * <p>Exists so the web UI can ask the domain "is this address routable, and via what?"
     * instead of reimplementing CIDR containment in JavaScript — the domain owns that knowledge.
     */
    Optional<LanAnchor> resolveLanAnchor(String lanAddress);
}
