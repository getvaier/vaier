package net.vaier.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * How often the operator may make Vaier go and ask the registries (#57 slice 3).
 *
 * <p>The daily sweep needs no protection from the anonymous rate limit: being daily <em>is</em> its
 * protection. An <b>update check</b> has none. It is driven by a button, and it deliberately refuses every
 * remembered answer — so it is the one path in Vaier where a human can turn impatience into a hundred manifest
 * requests. Docker Hub answers that with a 429; the sweep is total, so every image degrades to
 * {@link UpdateAvailability#UNKNOWN}; and the fleet goes blind at precisely the moment the operator was trying
 * to see it. The failure mode is perfectly inverted from the intent, which is why the floor is a rule and not
 * a nicety.
 *
 * <p><b>Coalesce, never queue and never lie.</b> A check inside the floor is refused outright rather than
 * deferred: the operator gets an immediate, true answer — when Vaier last really looked — instead of a spinner
 * that resolves into a claim it did something it did not. Nothing upstream can have moved in the seconds since
 * the last check anyway, so the refusal costs them no information at all.
 *
 * <p>The floor runs from the last <em>admitted</em> check, not the last attempt. Measuring from attempts would
 * let someone clicking every thirty seconds push the floor ahead of itself forever and never be allowed to
 * check at all — a limiter that answers impatience with a permanent refusal.
 */
public class UpdateCheckFloor {

    /**
     * Long enough that a burst of clicks is one question, short enough that an operator who has just finished
     * a {@code docker compose up -d} and switched windows never notices it. Their pull takes longer than this.
     */
    private static final Duration FLOOR = Duration.ofSeconds(60);

    private final Clock clock;
    private Instant lastCheckedAt;

    public UpdateCheckFloor(Clock clock) {
        this.clock = clock;
    }

    /**
     * The answer to "may I ask the registries now?", carrying the truth the operator is owed either way.
     *
     * @param admitted      whether the caller may go and check — and must, since the grant is already recorded
     * @param lastCheckedAt when Vaier last really looked: now when admitted, the earlier check when refused.
     *                      Never null and never invented — there is no admission before a first check, so
     *                      "never looked" is not a state any caller can observe.
     */
    public record Admission(boolean admitted, Instant lastCheckedAt) {}

    /**
     * Ask to check now. The first ask always wins; one inside {@link #FLOOR} of the last admitted check is
     * refused, and told when that check was.
     *
     * <p>Synchronized because two operators (or two clicks) racing must produce one sweep, not two: this is
     * the mutual exclusion that makes the floor a limit rather than a suggestion.
     */
    public synchronized Admission admit() {
        Instant now = clock.instant();
        if (lastCheckedAt != null && lastCheckedAt.plus(FLOOR).isAfter(now)) {
            return new Admission(false, lastCheckedAt);
        }
        lastCheckedAt = now;
        return new Admission(true, now);
    }
}
