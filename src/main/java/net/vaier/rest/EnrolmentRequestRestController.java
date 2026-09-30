package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.ApproveEnrolmentUseCase.ApprovedEnrolmentUco;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.LookUpEnrolmentTicketUseCase;
import net.vaier.application.NotifyAdminsOfEnrolmentRequestUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.RequestEnrolmentUseCase;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.EnrolmentVerdict;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.springframework.http.HttpStatus;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The join-code flow (#359 slice 1b): a phone asks to join and waits; the operator approves it from
 * whatever device they are already signed in on. It replaces signing into the Google account of
 * whoever owns the phone, in that phone's browser.
 *
 * <p>Two of these routes are anonymous, because a phone that has not joined yet has no session and
 * the whole point is that it never needs one. Both are narrow. {@code POST /vpn/enrolments} creates
 * nothing but a request and answers with a {@code Join code} and an {@code Enrolment ticket} — never
 * a word about the fleet. {@code GET /vpn/enrolments/{ticket}/events} is gated by 32 unguessable
 * random bytes and carries only that request's own verdict. The join code authorises nothing: it is
 * four digits so a human can tell which phone they are approving.
 *
 * <p>Everything else here falls through to the admin tier like any other route.
 */
@RestController
@RequestMapping("/vpn/enrolments")
@RequiredArgsConstructor
@Slf4j
public class EnrolmentRequestRestController {

    /** Where every signed-in operator watches for phones arriving, being approved and being refused. */
    private static final String OPERATOR_TOPIC = "enrolment-requests";

    /** One topic per waiting phone, keyed by the ticket only that phone holds. */
    private static final String TICKET_TOPIC_PREFIX = "enrolment:";

    private static final Base64.Encoder CONFIG_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RequestEnrolmentUseCase requestEnrolmentUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final ApproveEnrolmentUseCase approveEnrolmentUseCase;
    private final RefuseEnrolmentUseCase refuseEnrolmentUseCase;
    private final LookUpEnrolmentTicketUseCase lookUpEnrolmentTicketUseCase;
    private final NotifyAdminsOfEnrolmentRequestUseCase notifyAdminsOfEnrolmentRequestUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ForSubscribingToEvents forSubscribingToEvents;

    /** ANONYMOUS. A phone registers the key it minted and starts waiting. */
    @PostMapping
    public ResponseEntity<RequestEnrolmentResponse> request(@RequestBody RequestEnrolmentRequest body) {
        // The key, the name and the platform are judged in the domain (IllegalArgumentException -> 400), and the
        // cap on waiting phones is the domain's too (ConflictException -> 409).
        EnrolmentRequest opened = requestEnrolmentUseCase.request(body.name(), body.publicKey(), body.platform());
        log.info("Enrolment request from '{}' waiting with join code {}",
            LogSafe.forLog(opened.name()), opened.code());

        forPublishingEvents.publish(OPERATOR_TOPIC, "requested", opened.code());
        // The fleet page learns over the stream; whoever is not looking at it learns by mail.
        notifyAdminsOfEnrolmentRequestUseCase.notifyAdminsOfEnrolmentRequest(opened);
        return ResponseEntity.ok(new RequestEnrolmentResponse(opened.code(), opened.ticket(),
            opened.secondsLeft(System.currentTimeMillis())));
    }

    /**
     * ANONYMOUS, ticket-gated. The waiting phone's own stream. A ticket nobody holds — unknown,
     * refused or expired — is {@code 410 Gone}, one answer for all three.
     */
    @GetMapping(value = "/{ticket}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> ticketEvents(@PathVariable String ticket) {
        EnrolmentVerdict verdict = lookUpEnrolmentTicketUseCase.lookUp(ticket);
        if (verdict.isGone()) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        SseEmitter emitter = forSubscribingToEvents.subscribe(TICKET_TOPIC_PREFIX + ticket);
        if (verdict.isApproved()) {
            // The stream dropped while the operator was approving. Replay down the same topic the
            // live path uses, then close: the phone has everything it came for.
            forPublishingEvents.publish(TICKET_TOPIC_PREFIX + ticket, "approved",
                encodeConfig(verdict.configFile()));
            emitter.complete();
        }
        return ResponseEntity.ok(emitter);
    }

    /** ADMIN. The phones waiting right now, so the operator can match a code to a screen. */
    @GetMapping
    public ResponseEntity<List<PendingEnrolmentResponse>> pending() {
        long now = System.currentTimeMillis();
        return ResponseEntity.ok(listEnrolmentRequestsUseCase.pending().stream()
            .map(request -> new PendingEnrolmentResponse(request.code(), request.name(),
                request.publicKey(), request.machineType().name(), request.secondsLeft(now)))
            .toList());
    }

    /** ADMIN. The operator's live view of phones arriving, being approved and being refused. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter adminEvents() {
        return forSubscribingToEvents.subscribe(OPERATOR_TOPIC);
    }

    /** ADMIN. Admits the phone showing this join code. Its config goes to the phone, not here. */
    @PostMapping("/{code}/approve")
    public ResponseEntity<ApprovedEnrolmentResponse> approve(@PathVariable String code) {
        log.info("Approving join code {}", LogSafe.forLog(code));
        ApprovedEnrolmentUco approved = approveEnrolmentUseCase.approve(code);

        forPublishingEvents.publish(TICKET_TOPIC_PREFIX + approved.ticket(), "approved",
            encodeConfig(approved.device().configFile()));
        forPublishingEvents.publish("vpn-peers", "peers-updated", "");
        forPublishingEvents.publish(OPERATOR_TOPIC, "approved", code);

        MachineId machineId = approved.device().machineId();
        return ResponseEntity.ok(new ApprovedEnrolmentResponse(approved.device().id(),
            machineId == null ? null : machineId.value(), approved.device().name(),
            approved.device().ipAddress(), approved.device().peerType().name()));
    }

    /** ADMIN. Turns the phone away. An unknown code is still {@code 204} — there is nothing to undo. */
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> refuse(@PathVariable String code) {
        log.info("Refusing join code {}", LogSafe.forLog(code));
        refuseEnrolmentUseCase.refuse(code).ifPresent(refused -> {
            forPublishingEvents.publish(TICKET_TOPIC_PREFIX + refused.ticket(), "refused", "");
            forPublishingEvents.publish(OPERATOR_TOPIC, "refused", refused.code());
        });
        return ResponseEntity.noContent().build();
    }

    /** Base64url so the multi-line config rides in a single SSE data field. */
    private static String encodeConfig(String configFile) {
        return CONFIG_ENCODER.encodeToString(configFile.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code platform}: {@code "windows"}, or absent for a phone. */
    public record RequestEnrolmentRequest(String name, String publicKey, String platform) {}

    /** Everything here is about the caller's own request; nothing in it describes the fleet. */
    public record RequestEnrolmentResponse(String code, String ticket, long expiresInSeconds) {}

    /** No ticket: it is handed to the phone once and must never reach another screen. */
    public record PendingEnrolmentResponse(String code, String name, String publicKey,
                                           String machineType, long expiresInSeconds) {}

    /** No config: it belongs to the phone and travels on the phone's own stream. */
    public record ApprovedEnrolmentResponse(String id, String machineId, String name,
                                            String ipAddress, String machineType) {}
}
