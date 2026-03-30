package net.vaier.domain.port;

import java.util.Optional;

public interface ForDetectingPihole {
    Optional<String> detectPiholeIp();
}
