package net.vaier.domain.port;

import net.vaier.domain.GeoLocation;

import java.util.Optional;

public interface ForGeolocatingIps {
    Optional<GeoLocation> locate(String ipAddress);
}
