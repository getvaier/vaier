package net.vaier.domain;

import net.vaier.domain.port.ForGeolocatingIps;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BlockDecisionTest {

    private static BlockDecision.BlockDecisionBuilder banOn(String sourceIp) {
        return BlockDecision.builder()
            .id(1L)
            .scenario("crowdsecurity/http-probing")
            .sourceIp(sourceIp)
            .type("ban")
            .duration("3h0m40s");
    }

    @Test
    void labelReadsTheAddressScenarioAndDuration() {
        BlockDecision decision = banOn("1.2.3.4").duration("3h59m48.13179286s").build();

        assertThat(decision.label())
            .isEqualTo("1.2.3.4 — crowdsecurity/http-probing (ban, 3h59m48.13179286s)");
    }

    @Test
    void labelNamesTheCountryAndTheNetworkWhenCrowdSecEnrichedTheSource() {
        BlockDecision decision = banOn("195.178.110.155")
            .country("BG")
            .asnOrg("Techoff Srv Limited")
            .build();

        assertThat(decision.label()).isEqualTo(
            "195.178.110.155 (BG · Techoff Srv Limited) — crowdsecurity/http-probing (ban, 3h0m40s)");
    }

    @Test
    void labelNamesWhicheverHalfOfTheEnrichmentCrowdSecKnows() {
        assertThat(banOn("1.2.3.4").country("NO").build().label())
            .isEqualTo("1.2.3.4 (NO) — crowdsecurity/http-probing (ban, 3h0m40s)");
        assertThat(banOn("1.2.3.4").asnOrg("MICROSOFT-CORP-MSN-AS-BLOCK").build().label())
            .isEqualTo("1.2.3.4 (MICROSOFT-CORP-MSN-AS-BLOCK) — crowdsecurity/http-probing (ban, 3h0m40s)");
    }

    // A private-range source carries empty enrichment strings rather than absent ones — an empty country
    // is no country, and must not render as an empty pair of brackets.
    @Test
    void aBlankCountryOrNetworkIsNoEnrichmentAtAll() {
        BlockDecision decision = banOn("192.168.1.10").country("").asnOrg("  ").build();

        assertThat(decision.enriched()).isFalse();
        assertThat(decision.country()).isNull();
        assertThat(decision.asnOrg()).isNull();
        assertThat(decision.label()).isEqualTo("192.168.1.10 — crowdsecurity/http-probing (ban, 3h0m40s)");
    }

    @Test
    void locatableOnlyWhenCrowdSecResolvedBothCoordinates() {
        assertThat(banOn("195.178.110.155").latitude(42.696).longitude(23.332).build().locatable()).isTrue();
        assertThat(banOn("1.2.3.4").build().locatable()).isFalse();
        assertThat(banOn("1.2.3.4").latitude(42.696).build().locatable()).isFalse();
        assertThat(banOn("1.2.3.4").longitude(23.332).build().locatable()).isFalse();
    }

    // --- where a source sits is Vaier's own database's call, CrowdSec's only a fallback (#347) ----------

    @Test
    void placedBy_takesCoordinatesAndCountryFromVaiersOwnDatabase_andKeepsTheNetworkFromCrowdSec() {
        ForGeolocatingIps geo = ip -> "195.178.110.155".equals(ip)
            ? Optional.of(new GeoLocation(42.7, 23.3, "Sofia", "Bulgaria")) : Optional.empty();
        BlockDecision crowdSecs = banOn("195.178.110.155")
            .country("BG").asnOrg("Techoff Srv Limited").latitude(41.0).longitude(20.0).build();

        BlockDecision placed = crowdSecs.placedBy(geo);

        assertThat(placed.latitude()).isEqualTo(42.7);
        assertThat(placed.longitude()).isEqualTo(23.3);
        assertThat(placed.origin()).isEqualTo("Bulgaria · Techoff Srv Limited");
        assertThat(placed.id()).isEqualTo(crowdSecs.id());
        assertThat(placed.scenario()).isEqualTo(crowdSecs.scenario());
    }

    @Test
    void placedBy_fallsBackToCrowdSecsOwnPlacement_onlyWhenVaierCannotPlaceTheSource() {
        ForGeolocatingIps nowhere = ip -> Optional.empty();
        BlockDecision enrichedByCrowdSec = banOn("1.2.3.4").country("BG").latitude(42.696).longitude(23.332).build();
        BlockDecision unplaced = banOn("192.168.1.10").build();

        assertThat(enrichedByCrowdSec.placedBy(nowhere)).isEqualTo(enrichedByCrowdSec);
        // Neither source can place it: not located, and no sentinel coordinates to defend against.
        assertThat(unplaced.placedBy(nowhere).locatable()).isFalse();
        assertThat(unplaced.placedBy(nowhere).enriched()).isFalse();
    }

    // ...but a genuine zero on ONE axis is a real place — the equator and the prime meridian both run
    // through inhabited land, so only the 0/0 pair is the sentinel.
    @Test
    void aZeroOnOneAxisAloneIsStillARealPlace() {
        assertThat(banOn("1.2.3.4").latitude(0.0).longitude(23.332).build().locatable()).isTrue();
        assertThat(banOn("1.2.3.4").latitude(42.696).longitude(0.0).build().locatable()).isTrue();
    }

    @Test
    void coordinatesAreNotLabelMaterial() {
        BlockDecision decision = banOn("195.178.110.155")
            .country("BG").asnOrg("Techoff Srv Limited").latitude(42.696).longitude(23.332).build();

        assertThat(decision.label()).isEqualTo(
            "195.178.110.155 (BG · Techoff Srv Limited) — crowdsecurity/http-probing (ban, 3h0m40s)");
    }

    @Test
    void enrichedIsTrueAsSoonAsCrowdSecKnowsEitherHalf() {
        assertThat(banOn("1.2.3.4").build().enriched()).isFalse();
        assertThat(banOn("1.2.3.4").country("BG").build().enriched()).isTrue();
        assertThat(banOn("1.2.3.4").asnOrg("Techoff Srv Limited").build().enriched()).isTrue();
    }

    // --- what the notification path asks a decision -------------------------------------------------

    @Test
    void aDecisionCarriesTheKindOfThreatItsScenarioNames() {
        assertThat(banOn("1.2.3.4").scenario("crowdsecurity/http-probing").build().threatKind())
            .isEqualTo(ThreatKind.BLIND_SCANNING);
        assertThat(banOn("1.2.3.4").scenario("crowdsecurity/ssh-bf").build().threatKind())
            .isEqualTo(ThreatKind.CREDENTIAL_ATTACK);
    }

    /**
     * The predicate the lockout alarm turns on: a ban whose source is one of the operator's own networks
     * means the allowlist has stopped protecting them, not that somebody is attacking.
     */
    @Test
    void aBanOnTheOperatorsOwnNetworkLocksThemOut() {
        TrustedNetworks trusted = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.3.0/24"));

        assertThat(banOn("10.13.13.6").build().locksOut(trusted)).isTrue();
        assertThat(banOn("172.20.0.9").build().locksOut(trusted)).isTrue();
        assertThat(banOn("192.168.3.40").build().locksOut(trusted)).isTrue();
    }

    @Test
    void aBanOnAStrangerLocksNobodyOut() {
        TrustedNetworks trusted = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of());

        assertThat(banOn("195.178.110.155").build().locksOut(trusted)).isFalse();
    }

    /** No allowlist to judge against is no lockout — never a lockout alarm on missing information. */
    @Test
    void withoutAnyTrustedNetworksNothingLocksTheOperatorOut() {
        assertThat(banOn("10.13.13.6").build().locksOut(null)).isFalse();
    }

    // --- distinguishing a hand block from CrowdSec's own (#349) ----------------------------------------

    /**
     * The whole of #349's distinguishability requirement: a decision Vaier placed by hand carries the same
     * marker {@link SourceAddress#block} asks {@code cscli --reason} to write, and the read side classifies
     * by it rather than needing a second field CrowdSec's own wire format would have to carry.
     */
    @Test
    void handBlocked_recognisesTheReasonMarkerVaierWritesOnAHandBlock() {
        BlockDecision decision = banOn("1.2.3.4")
            .scenario(BlockDecision.handBlockReason("admin@example.com")).build();

        assertThat(decision.handBlocked()).isTrue();
        assertThat(decision.blockedByAdmin()).isEqualTo("admin@example.com");
    }

    @Test
    void handBlocked_isFalseForAnOrdinaryCrowdSecScenario() {
        BlockDecision decision = banOn("1.2.3.4").scenario("crowdsecurity/http-probing").build();

        assertThat(decision.handBlocked()).isFalse();
        assertThat(decision.blockedByAdmin()).isNull();
    }

    @Test
    void handBlocked_isFalseWithNoScenarioAtAll() {
        BlockDecision decision = banOn("1.2.3.4").scenario(null).build();

        assertThat(decision.handBlocked()).isFalse();
        assertThat(decision.blockedByAdmin()).isNull();
    }

    /** Whoever placed a hand block, an admin with no email on record still reads as one, not as nobody. */
    @Test
    void handBlockReason_fallsBackToAnAdminWhenNoEmailIsKnown() {
        assertThat(BlockDecision.handBlockReason(null)).isEqualTo("vaier: blocked by an admin");
        assertThat(BlockDecision.handBlockReason("  ")).isEqualTo("vaier: blocked by an admin");
    }
}
