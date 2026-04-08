package net.vaier.domain.port;

import net.vaier.domain.Server;

import java.util.Optional;

public interface ForGettingImageDigests {

    Optional<String> getImageDigest(Server server, String imageId);
}
