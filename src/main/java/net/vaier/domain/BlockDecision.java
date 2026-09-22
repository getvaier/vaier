package net.vaier.domain;

import lombok.Builder;
import net.vaier.domain.port.ForGeolocatingIps;

import java.util.StringJoiner;

/**
 * One active CrowdSec ban. CrowdSec's own field for the banned address is named {@code value};
 * {@link #sourceIp} is the ubiquitous-language name for it. {@code duration} is kept as CrowdSec's own
 * string (e.g. {@code "3h59m48.13179286s"}) rather than reparsed into a {@link java.time.Duration}, since
 * the operator only ever needs to read it, never compute with it. {@code id} is the identity
 * {@link BreachAttemptTracker} diffs sweeps on.
 *
 * <p>{@code country}, {@code asnOrg}, {@code latitude} and {@code longitude} say where the source sits and are
 * all optional. The ASN is CrowdSec's; the place is Vaier's own database's whenever it can answer
 * ({@link #placedBy}), with CrowdSec's own placement only as a fallback — one map, one authority (#347).
 * Mapping the wire shape onto this record belongs to the driven adapter — the record itself carries no
 * serialisation coupling, so where the decisions are read from can change without touching the domain.
 *
 * <p>Nine components, six of them strings and two of them {@code Double}, is exactly the shape that lets
 * two same-typed fields be swapped silently at a call site — hence {@link Builder}.
 */
@Builder(toBuilder = true)
public record BlockDecision(Long id, String scenario, String sourceIp, String type, String duration,
                            String country, String asnOrg, Double latitude, Double longitude) {

    public BlockDecision {
        // An empty country is no country. CrowdSec sends "" rather than omitting the field for a source it
        // could not place, and an operator must never read an empty pair of brackets in a breach mail.
        country = presentOrNull(country);
        asnOrg = presentOrNull(asnOrg);
    }

    /**
     * What kind of threat this decision's scenario names, and therefore whether it is worth an email at
     * all. See {@link ThreatKind} for the rule and for why most decisions are deliberately silent.
     */
    public ThreatKind threatKind() {
        return ThreatKind.of(scenario);
    }

    /**
     * Whether this ban is keeping the <em>operator</em> out. True when the banned source falls inside the
     * fleet's {@link TrustedNetworks} — the VPN subnet, the Docker bridge, a relay's LAN, or an address the
     * operator trusted by hand.
     *
     * <p>Nobody is attacking when this is true. It means the allowlist that is supposed to make this
     * impossible has stopped working, and the operator is about to lose the console they would fix it from
     * — #329's first-named risk. It is the opposite of a breach attempt and must never be reported as one.
     *
     * @param trustedNetworks the operator's own networks, or null when they could not be assembled — in
     *                        which case nothing locks anybody out, because an alarm raised on missing
     *                        information is worse than no alarm. This is a guard, not the production
     *                        policy: the sweep defers itself entirely when it cannot read the allowlist,
     *                        since without it a lockout would be indistinguishable from a stranger's ban
     *                        and could be mailed as a breach attempt — the one thing it must never be
     *                        called. Nothing in production reaches here with null.
     */
    public boolean locksOut(TrustedNetworks trustedNetworks) {
        return trustedNetworks != null && trustedNetworks.contains(sourceIp);
    }

    /** Whether CrowdSec could say anything about where this source sits. Either half is enough. */
    public boolean enriched() {
        return country != null || asnOrg != null;
    }

    /**
     * Whether this attempt can honestly be drawn on the map: both coordinates present. Absence is the only
     * "could not place" here — the CrowdSec adapter reads that format's {@code 0}/{@code 0} sentinel as
     * absence, so no null-island carve-out is needed and a genuine zero on one axis is a real place.
     *
     * <p>This is the domain's call, not the map's: a containment-style predicate that lives in JavaScript
     * is one every future caller has to rediscover.
     */
    public boolean locatable() {
        return latitude != null && longitude != null;
    }

    /**
     * This decision placed by Vaier's own geolocation database — the one that places machines — so both
     * kinds of pin on one map come from one authority. Coordinates and country are taken from it when it
     * can answer; the network (ASN) stays CrowdSec's, which is the more identifying half of the origin
     * line and which that database does not hold. When it cannot answer, CrowdSec's own placement stands.
     */
    public BlockDecision placedBy(ForGeolocatingIps geo) {
        if (sourceIp == null) return this;
        return geo.locate(sourceIp)
            .map(place -> toBuilder()
                .latitude(place.latitude())
                .longitude(place.longitude())
                .country(place.country() != null ? place.country() : country)
                .build())
            .orElse(this);
    }

    /**
     * How this reads to an operator: {@code "1.2.3.4 — crowdsecurity/http-probing (ban, 3h59m48s)"} bare,
     * and {@code "195.178.110.155 (BG · Techoff Srv Limited) — crowdsecurity/http-probing (ban, 3h0m40s)"}
     * when CrowdSec knew where the source sits.
     */
    public String label() {
        String origin = origin();
        return sourceIp + (origin.isEmpty() ? "" : " (" + origin + ")")
            + " — " + scenario + " (" + type + ", " + duration + ")";
    }

    /**
     * Where CrowdSec places this source, rendered for a person — {@code "BG · Techoff Srv Limited"}, or
     * either half alone, or empty when it could not place the source at all (i.e. exactly when
     * {@link #enriched()} is false).
     *
     * <p>Public, and deliberately so: the separator and the which-halves-are-known rule are one decision,
     * and every surface that shows an origin — the breach mail, the Security view, the Map popup — must
     * show the same one. A frontend that joins {@code country} and {@code asnOrg} itself has taken a copy
     * of this rule, and the copy is what drifts.
     */
    public String origin() {
        StringJoiner origin = new StringJoiner(" · ");
        if (country != null) origin.add(country);
        if (asnOrg != null) origin.add(asnOrg);
        return origin.toString();
    }

    private static String presentOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
