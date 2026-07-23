package net.vaier.domain;

public record GeoLocation(double latitude, double longitude, String city, String country) {

    /** Mean Earth radius. A sphere is ample here: nothing decided from this distance turns on a few km. */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Great-circle distance to {@code other} in kilometres — how far apart two machines really are, which is
     * what {@link SurvivalKitHosts} spreads survival kits by. Two machines in the same building return zero,
     * exactly, which is the answer that makes the dispersion rule reject the pair.
     */
    public double distanceKmTo(GeoLocation other) {
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
