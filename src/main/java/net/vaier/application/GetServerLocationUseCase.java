package net.vaier.application;

import java.util.Optional;

public interface GetServerLocationUseCase {

    Optional<ServerLocation> getServerLocation();

    /**
     * Static metadata about the Vaier server itself: where it is geographically, and the LAN/VPC
     * CIDR it sits on. Both groups are independent — the LAN CIDR comes from
     * {@code ForResolvingServerLanCidr} (env override or EC2 IMDSv2), the location from public
     * host + DB-IP geolocation — and either can be absent (`null` lat/lon when geolocation fails,
     * `null` {@code lanCidr} when neither env nor IMDS yields one). The dashboard renders whatever
     * is present: the Map tab places a marker only when lat/lon are non-null; the Vaier-server
     * machine card shows the {@code lanCidr} when set.
     */
    record ServerLocation(
        String publicHost,
        Double latitude,
        Double longitude,
        String city,
        String country,
        String lanCidr
    ) {}
}
