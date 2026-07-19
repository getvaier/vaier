package net.vaier.domain;

import java.time.Instant;
import java.util.Map;

/**
 * What came of an <b>update check</b> — the check the operator asked for (#57 slice 3).
 *
 * <p>Two facts, and the discipline is that both stay true. {@link #checked} is whether the registries were
 * actually asked <em>this time</em>, which {@link UpdateCheckFloor} may refuse. {@link #changed} is whether
 * any image's verdict moved as a result.
 *
 * <p>The pairing exists so the UI cannot round either one up. "Vaier checked and found nothing new" and "Vaier
 * did not check, and here is when it last did" are different sentences, and a button that reported the first
 * when the second was true would be the same species of lie as the stale mark this slice exists to clear — a
 * mark you know is wrong is a mark you learn to ignore.
 *
 * @param checked       whether the registries were really asked, rather than the check being coalesced away
 * @param changed       whether any verdict moved — the reason, and the only reason, to repaint
 * @param lastCheckedAt when Vaier last really looked: now for an admitted check, earlier for a coalesced one
 */
public record UpdateCheckOutcome(boolean checked, boolean changed, Instant lastCheckedAt) {

    /**
     * A check that really happened. It changed something iff the fleet's verdicts are not what they were —
     * decided here, once, so that the push and the operator's own answer cannot tell different stories.
     */
    public static UpdateCheckOutcome checked(Map<ScopedImage, UpdateAvailability> before,
                                             Map<ScopedImage, UpdateAvailability> after,
                                             Instant at) {
        return new UpdateCheckOutcome(true, !before.equals(after), at);
    }

    /**
     * A check the floor refused. Vaier did not ask, so it does not get to say it asked — it reports when it
     * last really looked and lets the operator judge whether that is recent enough.
     */
    public static UpdateCheckOutcome coalesced(Instant lastCheckedAt) {
        return new UpdateCheckOutcome(false, false, lastCheckedAt);
    }

    /**
     * Whether this outcome is worth pushing to open Explorers. Only a change is: the push exists to repaint,
     * and waking every open Explorer to redraw an identical page is noise. The browser that clicked learns
     * "nothing new" from its own response and needs no event to be told.
     */
    public boolean worthPublishing() {
        return changed;
    }
}
