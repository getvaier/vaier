package net.vaier.adapter.driven;

import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.port.ForHoldingEnrolmentRequests;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory store for the phones waiting to be approved into the fleet, keyed by join code. A
 * request lives ten minutes and at most five wait at once, so a small process-local map is the
 * right home — a request lost to a restart is simply made again from the app.
 *
 * <p>This adapter supplies only randomness and storage. Which code is free, whether a request is
 * still live and what a ticket is owed are all {@link EnrolmentRequest}'s decisions.
 */
@Component
public class InMemoryEnrolmentRequestStore implements ForHoldingEnrolmentRequests {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    // Insertion-ordered so the Explorer lists waiting phones in the order they arrived; guarded by
    // the monitor because opening a request reads the live codes before adding to them.
    private final Map<String, EnrolmentRequest> requestsByCode = new LinkedHashMap<>();

    @Override
    public synchronized EnrolmentRequest open(String name, String publicKey, String platform) {
        dropExpired();
        Set<String> taken = requestsByCode.keySet().stream().collect(Collectors.toUnmodifiableSet());
        String code = EnrolmentRequest.pickCode(taken, RANDOM::nextInt);
        byte[] ticketBytes = new byte[32];
        RANDOM.nextBytes(ticketBytes);
        EnrolmentRequest request = EnrolmentRequest.open(name, publicKey, platform, code,
            ENCODER.encodeToString(ticketBytes), System.currentTimeMillis());
        requestsByCode.put(code, request);
        return request;
    }

    @Override
    public synchronized List<EnrolmentRequest> livePending() {
        dropExpired();
        return requestsByCode.values().stream().filter(r -> !r.isApproved()).toList();
    }

    @Override
    public synchronized Optional<EnrolmentRequest> findByCode(String code) {
        dropExpired();
        return Optional.ofNullable(code).map(requestsByCode::get);
    }

    @Override
    public synchronized Optional<EnrolmentRequest> findByTicket(String ticket) {
        dropExpired();
        if (ticket == null) {
            return Optional.empty();
        }
        return requestsByCode.values().stream().filter(r -> ticket.equals(r.ticket())).findFirst();
    }

    @Override
    public synchronized void recordApproval(String code, String configFile) {
        dropExpired();
        EnrolmentRequest request = code == null ? null : requestsByCode.get(code);
        if (request != null) {
            requestsByCode.put(code, request.approved(configFile));
        }
    }

    @Override
    public synchronized Optional<EnrolmentRequest> remove(String code) {
        dropExpired();
        return Optional.ofNullable(code).map(requestsByCode::remove);
    }

    /** Opportunistically evicts requests past their TTL so nothing outlives the ten minutes. */
    private void dropExpired() {
        long now = System.currentTimeMillis();
        requestsByCode.values().removeIf(request -> !request.isLive(now));
    }
}
