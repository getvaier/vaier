package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetAccessSourcesUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetTrustedAddressesUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UntrustAddressUseCase;
import net.vaier.domain.AccessSource;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * The security view's server side (#329 Slice 3): who CrowdSec is currently keeping out, and the two
 * things the operator can do about it — let one address back in now, or trust it for good.
 *
 * <p>Every path here is non-whitelisted, so Traefik's tier-3 catch-all puts it behind the admin auth chain
 * like every other fleet endpoint. There is no Java-side auth in this project and none is added here;
 * equally, nothing here is added to any anonymous allowlist. Who is blocked, and the power to unblock
 * them, is never anonymous.
 *
 * <p>Validation is the domain's: {@code SourceAddress.of} admits nothing but a dotted quad, so a
 * malformed or shell-flavoured address never reaches an exec argument and surfaces here as a uniform
 * {@code 400} through {@link GlobalExceptionHandler}. A failed unban surfaces as {@code 502} — the far
 * side refused — and never as a quiet success. A failed <em>read</em> surfaces as {@code 502} for the same
 * reason: "nobody is blocked" is what an empty list says on screen, and only CrowdSec gets to say it.
 */
@RestController
@RequestMapping("/security")
@RequiredArgsConstructor
@Slf4j
public class SecurityRestController {

    /**
     * The event this controller pushes when the operator's trusted list changes (#348). It lives here rather
     * than beside {@code BreachAttemptWatcher.DECISIONS_EVENT} because nothing on a clock ever publishes it:
     * the trusted list changes only when a person decides something, and this is the only place that
     * happens.
     */
    static final String TRUSTED_ADDRESSES_EVENT = "trusted-addresses";

    private final GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    private final LiftBlockUseCase liftBlockUseCase;
    private final TrustAddressUseCase trustAddressUseCase;
    private final GetTrustedAddressesUseCase getTrustedAddressesUseCase;
    private final UntrustAddressUseCase untrustAddressUseCase;
    private final GetAccessSourcesUseCase getAccessSourcesUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ForSubscribingToEvents forSubscribingToEvents;
    private final ObjectMapper objectMapper;

    /**
     * Who is blocked right now, so the view can paint on load or reconnect. A read that fails is a
     * {@code 502}, not an empty list — the view renders empty as "nobody is blocked right now".
     */
    @GetMapping("/decisions")
    public List<BlockDecisionResponse> decisions() {
        return responses(getBlockDecisionsUseCase.getBlockDecisions());
    }

    /**
     * The security view's SSE stream. The browser never polls: it opens this and repaints on the
     * {@code block-decisions} events {@link BreachAttemptWatcher} pushes every sweep — and on the one a
     * mutation below pushes immediately, so an unblock is visible at once rather than up to five minutes
     * later.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return forSubscribingToEvents.subscribe(BreachAttemptWatcher.SECURITY_TOPIC);
    }

    /** Let one address back in now. One-off — the next matching scenario may block it again. */
    @DeleteMapping("/decisions/{sourceIp}")
    public ResponseEntity<Void> liftBlock(@PathVariable String sourceIp) {
        log.info("Lifting the block on {}", LogSafe.forLog(sourceIp));
        liftBlockUseCase.liftBlock(sourceIp);
        publishDecisions();
        return ResponseEntity.ok().build();
    }

    /**
     * Trust one address for good. It is unblocked now and joins the trusted networks; the whitelist itself
     * takes effect from CrowdSec's next restart, which Vaier deliberately does not trigger — see
     * {@link TrustAddressUseCase}.
     */
    @PostMapping("/trusted-addresses")
    public ResponseEntity<Void> trustAddress(@RequestBody TrustAddressRequest request) {
        log.info("Trusting {}", LogSafe.forLog(request.sourceIp()));
        trustAddressUseCase.trustAddress(request.sourceIp());
        publishDecisions();
        publishTrustedAddresses();
        return ResponseEntity.ok().build();
    }

    /**
     * What the operator has trusted by hand — and nothing else. The structural trusted networks (the VPN
     * subnet, the Docker bridge, every relay peer's LAN) are deliberately absent: they are what stops the
     * bouncer blocking the operator's own traffic, removing one is the lockout {@code LockoutWarning}
     * exists to shout about, and a payload that carried both kinds would put one a click from the untrust
     * verb below. This controller does not even hold {@code GetTrustedNetworksUseCase}, so there is nothing
     * here to leak.
     */
    @GetMapping("/trusted-addresses")
    public List<TrustedAddressResponse> trustedAddresses() {
        return trustedResponses(getTrustedAddressesUseCase.getTrustedAddresses());
    }

    /**
     * Stop trusting one address. It is not blocked by this — Vaier never blocks anyone — it simply goes back
     * to being judged on its behaviour, and it leaves CrowdSec's whitelist at CrowdSec's next restart, which
     * Vaier still does not trigger. Untrusting an address that is not in the list succeeds: see
     * {@link UntrustAddressUseCase}.
     */
    @DeleteMapping("/trusted-addresses/{sourceIp}")
    public ResponseEntity<Void> untrustAddress(@PathVariable String sourceIp) {
        log.info("No longer trusting {}", LogSafe.forLog(sourceIp));
        untrustAddressUseCase.untrustAddress(sourceIp);
        publishTrustedAddresses();
        return ResponseEntity.ok().build();
    }

    /**
     * Where the people Vaier lets in come from — one entry per place, for the map's green dots. The other
     * half of what this controller serves: the threat reads above say who was kept out, this one says who
     * got in. Served from here rather than a controller of its own because it is the same domain and the
     * same SSE topic, even though the surface that draws it is the Map.
     *
     * <p>Authenticated like everything else here, and deliberately: the list names the places and the
     * people who reach this fleet, which is not something to hand to an anonymous caller.
     */
    @GetMapping("/access-sources")
    public List<AccessSourceResponse> accessSources() {
        return accessSourceResponses(getAccessSourcesUseCase.getAccessSources());
    }

    /**
     * Push the decisions as they stand now, so the view updates the moment something changed instead of
     * waiting for the next five-minute sweep — the {@code PublishedServiceRestController} pattern: act,
     * then publish. Called only after the action succeeded; a failed action publishes nothing, since a
     * refreshed list would read as though something had happened.
     *
     * <p>A push that fails must not turn a completed unban into an error response, so it is logged and
     * dropped — the next sweep repaints anyway.
     */
    private void publishDecisions() {
        try {
            forPublishingEvents.publish(BreachAttemptWatcher.SECURITY_TOPIC,
                BreachAttemptWatcher.DECISIONS_EVENT,
                objectMapper.writeValueAsString(responses(getBlockDecisionsUseCase.getBlockDecisions())));
        } catch (Exception e) {
            log.debug("Publishing the refreshed block decisions failed: {}", e.getMessage());
        }
    }

    /**
     * Act, then publish, exactly as {@link #publishDecisions()} does and for the same reason — the operator
     * must watch the address leave the list on the click. Nothing on a clock refreshes this one, so this is
     * the only push it ever gets; a failure here is still logged and dropped rather than turning a completed
     * change into an error, and the view re-reads on its next SSE reconnect.
     */
    private void publishTrustedAddresses() {
        try {
            forPublishingEvents.publish(BreachAttemptWatcher.SECURITY_TOPIC, TRUSTED_ADDRESSES_EVENT,
                objectMapper.writeValueAsString(
                    trustedResponses(getTrustedAddressesUseCase.getTrustedAddresses())));
        } catch (Exception e) {
            log.debug("Publishing the refreshed trusted addresses failed: {}", e.getMessage());
        }
    }

    static List<BlockDecisionResponse> responses(List<BlockDecision> decisions) {
        return decisions.stream().map(BlockDecisionResponse::from).toList();
    }

    static List<TrustedAddressResponse> trustedResponses(List<SourceAddress> addresses) {
        return addresses.stream().map(a -> new TrustedAddressResponse(a.value())).toList();
    }

    static List<AccessSourceResponse> accessSourceResponses(List<AccessSource> sources) {
        return sources.stream().map(AccessSourceResponse::from).toList();
    }

    /** Which address to trust. */
    record TrustAddressRequest(String sourceIp) {}

    /**
     * One address the operator trusts. A bare dotted quad, never the {@code /32} form the whitelist file
     * carries: the operator trusted an address, and that is what the list should say back to them.
     */
    record TrustedAddressResponse(String sourceIp) {}

    /**
     * One active ban as the browser sees it — the same shape the REST read returns and the SSE topic
     * carries, built in one place so the two can never drift apart.
     *
     * <p>{@code locatable} and {@code enriched} are carried as the domain decided them, <b>not</b> left
     * for the browser to re-derive from {@code latitude}/{@code longitude}. That matters concretely:
     * {@code 0} is falsy in JavaScript, and a frontend truthiness check would quietly drop a genuine zero
     * on one axis — the equator and the prime meridian both run through inhabited land. The raw
     * coordinates ride along because the map needs numbers to draw with; the <em>decision</em> whether to
     * draw at all is already made.
     */
    record BlockDecisionResponse(Long id, String scenario, String sourceIp, String type, String duration,
                                 String country, String asnOrg, Double latitude, Double longitude,
                                 boolean enriched, boolean locatable, String origin, String label) {

        static BlockDecisionResponse from(BlockDecision decision) {
            return new BlockDecisionResponse(decision.id(), decision.scenario(), decision.sourceIp(),
                decision.type(), decision.duration(), decision.country(), decision.asnOrg(),
                decision.latitude(), decision.longitude(),
                decision.enriched(), decision.locatable(), decision.origin(), decision.label());
        }
    }

    /**
     * One place allowed accesses came from, as the browser sees it — the same shape the REST read returns
     * and the {@code access-sources} SSE event carries, built in one place so the two cannot drift.
     *
     * <p>{@code locatable} is carried as the domain decided it, for the reason spelled out on
     * {@link BlockDecisionResponse}: {@code 0} is falsy in JavaScript, and a browser-side
     * {@code if (latitude)} would quietly delete the equator. The unplaceable bucket arrives as one element
     * with null city, country and coordinates and {@code locatable: false} — the map has no dot to draw for
     * it and shows its count as a note beside itself, rather than pretending those accesses never happened.
     *
     * <p>{@code place} rides along for the same reason {@code BlockDecisionResponse} carries
     * {@code origin}: how a place reads to a person — which halves are known, what separates them, what
     * the unplaceable bucket is called — is one decision, and a frontend that joined {@code city} and
     * {@code country} itself would have taken a copy of it. The raw halves are still here for anything
     * that needs to sort or group by them.
     *
     * <p>The timestamps are rendered as ISO-8601 strings here rather than left to Jackson, so the wire
     * format is a property of this record and not of whatever the {@code ObjectMapper} is configured with.
     */
    record AccessSourceResponse(String city, String country, String place,
                                Double latitude, Double longitude,
                                boolean locatable, long count, String firstSeen, String lastSeen,
                                List<String> people) {

        static AccessSourceResponse from(AccessSource source) {
            return new AccessSourceResponse(source.city(), source.country(), source.place(),
                source.latitude(), source.longitude(), source.locatable(), source.count(),
                source.firstSeen().toString(), source.lastSeen().toString(), source.people());
        }
    }
}
