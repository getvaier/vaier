package net.vaier.domain.port;

import net.vaier.domain.EnrolmentRequest;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for the devices currently waiting to be approved into the fleet (#359 slice 1b). The
 * store mints the {@code Enrolment ticket}'s randomness and holds the requests; every judgement
 * about them — liveness, ticket authorisation, code uniqueness, how many may wait — lives on
 * {@link EnrolmentRequest}.
 *
 * <p>Implemented only by an {@code *Adapter} (a store) — never by a service.
 */
public interface ForHoldingEnrolmentRequests {

    /**
     * Opens a request for a device that minted its own keypair: a cryptographically-random ticket, a
     * join code no waiting device is already showing, and the ~10-minute TTL.
     *
     * @param platform {@code "windows"}, or {@code null} for a phone
     * @throws IllegalArgumentException if the key, the name or the platform is refused — judged
     *                                  before anything is stored
     */
    EnrolmentRequest open(String name, String publicKey, String platform);

    /** The devices still waiting, in the order they arrived. Never an already-approved request. */
    List<EnrolmentRequest> livePending();

    /** The live request showing this join code, approved or not. Empty for anything unknown or expired. */
    Optional<EnrolmentRequest> findByCode(String code);

    /** The live request that issued this ticket, approved or not. Empty for anything unknown or expired. */
    Optional<EnrolmentRequest> findByTicket(String ticket);

    /**
     * Records the config an approval produced against the request, which then keeps living until its
     * TTL so a device whose stream dropped can reconnect and still be served. A no-op for an unknown
     * or expired code.
     */
    void recordApproval(String code, String configFile);

    /** Removes the request with this join code, returning what was removed so a refusal can reach it. */
    Optional<EnrolmentRequest> remove(String code);
}
