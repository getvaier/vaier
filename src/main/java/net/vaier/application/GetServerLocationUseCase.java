package net.vaier.application;

import java.util.Optional;

public interface GetServerLocationUseCase {

    Optional<ServerLocation> getServerLocation();

    record ServerLocation(
        String publicHost,
        double latitude,
        double longitude,
        String city,
        String country
    ) {}
}
