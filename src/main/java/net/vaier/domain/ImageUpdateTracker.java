package net.vaier.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-image update-available state, so the watcher mails admins only when an image <b>becomes</b> out of date
 * — not every sweep, for as long as it stays out of date. The sibling of {@link RemoteDiskPressureTracker} and
 * {@link PeerConnectivityTracker}: it reports edge transitions and nothing else, and the watcher decides only
 * whom to tell.
 *
 * <p>Two rules differ from its siblings, and both are deliberate:
 *
 * <p><b>It is not baseline-quiet.</b> The disk and peer trackers swallow their first observation so a Vaier
 * restart raises no noise, which is right for a level that is true only at this instant. Staleness is not that
 * — it persists, and it is what #57 is about: a vaultwarden image that was <em>already</em> stale, quietly
 * breaking mobile sync. If the first sweep after a restart were silent, the very case this feature exists for
 * would be the case it never reported. So a first sighting of an out-of-date image is news.
 *
 * <p><b>{@link UpdateAvailability#UNKNOWN} is not a change.</b> An unreachable or rate-limited registry must
 * not be read as the operator having pulled the image: if it were, the next successful sweep would re-mail
 * them about an image they were told about already, and the flapping would teach them to filter the alert.
 * Unknown therefore leaves the last known verdict standing, untouched.
 */
public class ImageUpdateTracker {

    /** Image → whether it was last *known* to be out of date. Unknown verdicts never write here. */
    private final Map<String, Boolean> lastKnown = new ConcurrentHashMap<>();

    /**
     * Record a sweep's verdicts and report the images that have <b>just</b> become out of date, alphabetically
     * so a rollup email reads the same way twice.
     *
     * <p>Images absent from {@code verdicts} are forgotten: the container is gone, and if that image ever
     * comes back stale it is news again rather than a silence.
     */
    public synchronized List<String> update(Map<String, UpdateAvailability> verdicts) {
        List<String> newlyOutOfDate = new ArrayList<>();

        for (Map.Entry<String, UpdateAvailability> entry : verdicts.entrySet()) {
            UpdateAvailability verdict = entry.getValue();
            if (verdict == null || verdict == UpdateAvailability.UNKNOWN) {
                continue;   // Cannot tell — leave what was known standing, and say nothing.
            }
            String image = entry.getKey();
            boolean outOfDate = verdict.isUpdateAvailable();
            Boolean previous = lastKnown.put(image, outOfDate);
            if (outOfDate && !Boolean.TRUE.equals(previous)) {
                newlyOutOfDate.add(image);
            }
        }

        lastKnown.keySet().removeIf(image -> !verdicts.containsKey(image));
        Collections.sort(newlyOutOfDate);
        return newlyOutOfDate;
    }
}
