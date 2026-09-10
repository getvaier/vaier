package net.vaier.domain;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * A phone waiting to be approved into the fleet (#359 slice 1b). It exists so an operator can admit
 * someone else's phone from whatever device they are already signed in on, instead of signing into
 * that phone's browser.
 *
 * <p>Two values ride along and they are not interchangeable. The {@code Join code} is four digits,
 * shown on the phone and in the Explorer, and it <b>authorises nothing</b> — it is there so a human
 * can tell which phone they are approving. The {@code Enrolment ticket} is 32 unguessable random
 * bytes, returned to the phone once and never shown anywhere else, and it is the only thing that
 * gates delivery of the config.
 *
 * <p>The device's own public key and its name are judged in {@link #open} — the same two judgements
 * enrolment makes — so a bad request is refused while it is still anonymous and nothing is stored.
 *
 * <p>An approved request keeps living, now carrying its config, until the TTL. That is deliberately
 * looser than "burned on delivery": a phone whose stream dropped while the operator was approving
 * reconnects and is served again. Re-delivery only ever reaches the holder of an unguessable ticket,
 * and the window closes at the TTL like everything else here.
 *
 * @param configFile the approved peer's installable config; {@code null} while the request is pending.
 */
public record EnrolmentRequest(String code, String ticket, String name, String publicKey,
                               long expiresAtEpochMs, String configFile) {

    /** How long a phone may wait to be approved before its request disappears. */
    public static final Duration TTL = Duration.ofMinutes(10);

    /** How many phones may be waiting at once — the whole size of the anonymous surface. */
    public static final int MAX_PENDING = 5;

    /** Four digits: 0000-9999. */
    private static final int CODE_SPACE = 10_000;

    /**
     * Opens a request for a device that has minted its own keypair, judging both the key and the name
     * before anything can be stored.
     *
     * @throws IllegalArgumentException if the key is not a WireGuard key or the name slugs to nothing
     */
    public static EnrolmentRequest open(String name, String publicKey, String code, String ticket,
                                        long nowEpochMs) {
        WireGuardKey deviceKey = WireGuardKey.of(publicKey);
        // Judged, not kept: the peer id is minted at approval, against the peers on disk then.
        PeerId.sanitized(name);
        return new EnrolmentRequest(code, ticket, name, deviceKey.value(),
            nowEpochMs + TTL.toMillis(), null);
    }

    /** Whether another phone may start waiting, given how many already are. */
    public static boolean mayOpenAnother(int livePending) {
        return livePending < MAX_PENDING;
    }

    /**
     * Picks a join code no phone currently waiting is already showing — two identical codes on two
     * screens would leave the operator unable to tell which one they were approving. Takes a single
     * draw from {@code randomness} and steps forward from it, so it always terminates.
     */
    public static String pickCode(Set<String> taken, IntSupplier randomness) {
        int start = Math.floorMod(randomness.getAsInt(), CODE_SPACE);
        for (int step = 0; step < CODE_SPACE; step++) {
            String candidate = "%04d".formatted((start + step) % CODE_SPACE);
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Every join code is taken");
    }

    /** Expiry is exclusive: a request is dead at its expiry instant, as a setup token is. */
    public boolean isLive(long nowEpochMs) {
        return nowEpochMs < expiresAtEpochMs;
    }

    /** Whole seconds the phone has left to be approved; never negative. */
    public long secondsLeft(long nowEpochMs) {
        return Math.max(0, (expiresAtEpochMs - nowEpochMs) / 1000);
    }

    /** True iff this is the ticket this request issued and the request is still live. */
    public boolean authorizes(String presentedTicket, long nowEpochMs) {
        return ticket.equals(presentedTicket) && isLive(nowEpochMs);
    }

    public boolean isApproved() {
        return configFile != null;
    }

    /** The same request, now carrying the config the approval produced. */
    public EnrolmentRequest approved(String approvedConfigFile) {
        return new EnrolmentRequest(code, ticket, name, publicKey, expiresAtEpochMs, approvedConfigFile);
    }

    /** What the holder of {@code presentedTicket} is owed by this request right now. */
    public EnrolmentVerdict verdictFor(String presentedTicket, long nowEpochMs) {
        if (!authorizes(presentedTicket, nowEpochMs)) {
            return EnrolmentVerdict.gone();
        }
        return isApproved() ? EnrolmentVerdict.approved(configFile) : EnrolmentVerdict.pending();
    }

    /**
     * The waiting phone showing {@code code}, for whoever names one by it — the operator, or the model
     * proposing to let it in. A code nobody is showing is refused in words, never answered with nothing.
     */
    public static EnrolmentRequest byCode(List<EnrolmentRequest> pending, String code) {
        String wanted = code == null ? "" : code.trim();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Say which code.");
        }
        return pending.stream()
            .filter(request -> wanted.equals(request.code()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No phone is waiting with join code " + wanted + "."));
    }
}
