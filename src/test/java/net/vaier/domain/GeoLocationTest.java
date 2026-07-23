package net.vaier.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** How far apart two places are — the measure behind spreading survival kits across the map. */
class GeoLocationTest {

    private static final GeoLocation OSLO = new GeoLocation(59.9139, 10.7522, "Oslo", "NO");
    private static final GeoLocation MADRID = new GeoLocation(40.4168, -3.7038, "Madrid", "ES");
    private static final GeoLocation FRANKFURT = new GeoLocation(50.1109, 8.6821, "Frankfurt", "DE");

    @Test
    void twoCitiesAreTheirGreatCircleDistanceApart() {
        // Oslo to Madrid is about 2,390 km, Oslo to Frankfurt about 1,100. A rough figure is all this is
        // for: it decides which of several machines is furthest from the others, never anything that needs
        // surveying accuracy.
        assertThat(OSLO.distanceKmTo(MADRID)).isCloseTo(2388, org.assertj.core.data.Offset.offset(15.0));
        assertThat(OSLO.distanceKmTo(FRANKFURT)).isCloseTo(1098, org.assertj.core.data.Offset.offset(15.0));
    }

    @Test
    void distanceIsTheSameBothWays() {
        assertThat(OSLO.distanceKmTo(MADRID)).isEqualTo(MADRID.distanceKmTo(OSLO));
    }

    @Test
    void aPlaceIsNoDistanceFromItself() {
        // Two machines in the same building geolocate identically. Zero is the answer that makes the
        // dispersion rule reject the pair, so it has to be exactly zero and not a rounding artefact.
        assertThat(OSLO.distanceKmTo(new GeoLocation(59.9139, 10.7522, "Oslo", "NO"))).isZero();
    }
}
