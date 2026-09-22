package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.BlockNotLiftedException;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForExecutingInContainer;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForLiftingBlocks;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads CrowdSec's active block decisions by running {@code cscli decisions list -o json} inside the
 * {@code crowdsec} container (#329 Slice 3a), over the same {@link ForExecutingInContainer} port
 * {@code WireGuardVpnAdapter} already uses — the socket proxy has {@code EXEC=1} open, so this needs
 * <em>no</em> credential at all, where LAPI needed a bouncer key and its richer {@code /v1/alerts}
 * endpoint would have needed a second, JWT-based machine credential.
 *
 * <p>What that buys is enrichment: {@code cscli} returns the full alerts — each with the {@code source}
 * CrowdSec resolved (country, ASN) and a nested {@code decisions} array. One alert therefore yields
 * <em>n</em> {@link BlockDecision}s, each carrying its alert's source enrichment.
 *
 * <p>It carries a second port, {@link ForLiftingBlocks} (#329 Slice 3c), rather than a second adapter:
 * this is the one place in Vaier that speaks {@code cscli}, and splitting the two directions across two
 * classes would only duplicate the container name and the exec idiom.
 *
 * <p>This class is the only place in Vaier that knows what a CrowdSec failure looks like — a dead exec, an
 * error line where JSON was expected, the literal word {@code null} a quiet stack prints — and it is
 * therefore the only place that decides which of those is a failure at all. Both reads on
 * {@link ForDetectingIntrusions} parse identically and differ solely in what they do with a failure: the
 * silent one returns an empty list for the breach-attempt sweep, the loud one throws
 * {@link BlockDecisionsUnreadableException} for the operator's screen. See that port for why both are right.
 *
 * <p>A single unreadable alert is skipped by both rather than losing the whole sweep, the same per-entry
 * tolerance {@link DiskWatchFileAdapter} applies when loading its file.
 */
@Component
@Slf4j
public class CrowdSecCliAdapter implements ForDetectingIntrusions, ForLiftingBlocks {

    private static final String CROWDSEC_CONTAINER = "crowdsec";

    private final ForExecutingInContainer forExecutingInContainer;
    private final ForGeolocatingIps forGeolocatingIps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrowdSecCliAdapter(ForExecutingInContainer forExecutingInContainer, ForGeolocatingIps forGeolocatingIps) {
        this.forExecutingInContainer = forExecutingInContainer;
        this.forGeolocatingIps = forGeolocatingIps;
    }

    /**
     * The silent read, for the breach-attempt sweep: whatever went wrong, the answer is an empty list.
     * Deliberately not the same as {@link #getActiveDecisionsOrFail()}, and deliberately not reachable by a
     * caller who did not name it — see {@link ForDetectingIntrusions}.
     */
    @Override
    public List<BlockDecision> getActiveDecisionsOrEmpty() {
        try {
            return getActiveDecisionsOrFail();
        } catch (RuntimeException e) {
            // Broad on purpose, not by omission: the contract is "never throws", and the caller is a
            // scheduled sweep that would lose both its cycle and the operator's email to anything that got
            // past a narrower catch.
            log.debug("Could not read CrowdSec's active decisions: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The loud read, for the operator's security screen. Every failure to <em>ask</em> throws; only an
     * answer of "nobody" returns empty.
     *
     * <p>Two shapes are answers, not failures. A quiet stack prints the literal word {@code null} rather
     * than {@code []} — valid JSON, simply not an array — and an alert Vaier cannot parse is skipped
     * per-entry. Everything else is Vaier failing to ask: a dead exec, or output that is not JSON at all,
     * which is how {@code cscli} reports its own errors ({@code Error: unable to load config}). Reading
     * those as "nobody is blocked" is the defect this method exists to close — found live, where the first
     * read after a container restart failed cold and the view said nobody was blocked while eleven
     * decisions were active.
     */
    @Override
    public List<BlockDecision> getActiveDecisionsOrFail() {
        JsonNode alerts = readAlerts();
        if (alerts == null || alerts.isNull()) return List.of();
        if (!alerts.isArray()) {
            throw new BlockDecisionsUnreadableException(
                "Vaier could not read who CrowdSec is blocking: cscli answered with something other than a "
                    + "list of alerts.");
        }
        List<BlockDecision> decisions = new ArrayList<>();
        for (JsonNode alert : alerts) {
            collectDecisionsOf(alert, decisions);
        }
        return decisions;
    }

    private JsonNode readAlerts() {
        try {
            return objectMapper.readTree(forExecutingInContainer.execute(
                CROWDSEC_CONTAINER, "cscli", "decisions", "list", "-o", "json"));
        } catch (Exception e) {
            throw new BlockDecisionsUnreadableException(
                "Vaier could not ask CrowdSec who it is blocking: " + e.getMessage(), e);
        }
    }

    /**
     * {@code cscli decisions delete -i <ip>} — the one command Vaier ever issues that changes CrowdSec's
     * mind, and the same container exec the read path uses, so it needs no credential either.
     *
     * <p>Every argument is its own array element on {@link ForExecutingInContainer#execute}: there is no
     * shell and no string concatenation anywhere on this path, so the address cannot be read as anything
     * but a single argument even if validation upstream were ever weakened. It is a
     * {@link SourceAddress}, so it has already passed the domain's dotted-quad-only gate.
     *
     * <p>Deliberately unlike {@link #getActiveDecisionsOrEmpty()}: a failure here throws. An operator is waiting
     * to learn whether the address is back in, and a swallowed failure would tell them it is when it is
     * not. An address that was not banned is <em>not</em> a failure — cscli prints
     * {@code 0 decision(s) deleted} and exits happily, which is the operator getting what they asked for.
     */
    @Override
    public void liftBlock(SourceAddress address) {
        try {
            String output = forExecutingInContainer.execute(
                CROWDSEC_CONTAINER, "cscli", "decisions", "delete", "-i", address.value());
            // Safe to log unescaped: SourceAddress admits nothing but a dotted quad, so no newline can
            // reach this line to forge a second one.
            log.info("Lifted the CrowdSec block on {}: {}", address.value(), output.strip());
        } catch (Exception e) {
            throw new BlockNotLiftedException(
                "Vaier could not lift the block on " + address.value() + ": " + e.getMessage(), e);
        }
    }

    private void collectDecisionsOf(JsonNode alert, List<BlockDecision> into) {
        JsonNode alertDecisions = alert.path("decisions");
        if (!alertDecisions.isArray()) {
            log.debug("Skipping a CrowdSec alert with no readable decisions");
            return;
        }
        JsonNode source = alert.path("source");
        String country = textOrNull(source, "cn");
        String asnOrg = textOrNull(source, "as_name");
        // An unreadable coordinate costs the decision its place on the map, never its place in the sweep.
        // 0/0 is this wire format's way of saying "could not place" (null island, off Ghana), so it is read
        // as no coordinates here; a zero on one axis alone is a real place and stays.
        Double latitude = numberOrNull(source, "latitude");
        Double longitude = numberOrNull(source, "longitude");
        if (latitude != null && longitude != null && latitude == 0.0 && longitude == 0.0) {
            latitude = null;
            longitude = null;
        }
        for (JsonNode alertDecision : alertDecisions) {
            JsonNode id = alertDecision.path("id");
            if (!id.isNumber()) {
                // The id is what BreachAttemptTracker diffs sweeps on; without it the decision would look
                // brand new on every sweep and mail the operator forever.
                log.debug("Skipping a CrowdSec decision with no id");
                continue;
            }
            into.add(BlockDecision.builder()
                .id(id.asLong())
                .scenario(textOrNull(alertDecision, "scenario"))
                .sourceIp(textOrNull(alertDecision, "value"))
                .type(textOrNull(alertDecision, "type"))
                .duration(textOrNull(alertDecision, "duration"))
                .country(country)
                .asnOrg(asnOrg)
                .latitude(latitude)
                .longitude(longitude)
                .build()
                // Vaier's own database places the source — the same one that places machines (#347).
                .placedBy(forGeolocatingIps));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static Double numberOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
