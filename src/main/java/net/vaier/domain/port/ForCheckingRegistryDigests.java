package net.vaier.domain.port;

import java.util.Optional;

public interface ForCheckingRegistryDigests {

    Optional<String> getRemoteDigest(String image, String tag);
}
