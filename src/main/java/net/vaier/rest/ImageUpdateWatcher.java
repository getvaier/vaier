package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.application.SweepImageUpdatesUseCase;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.UpdateAvailability;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Asks the registries once a day whether anything in the fleet has an <b>update available</b>, and mails admins
 * when something newly has.
 *
 * <p>#57, and the reason it was reopened: a stale {@code vaultwarden} image on a server peer broke Bitwarden
 * mobile sync for who knows how long. Pulling it fixed it in seconds. The operator had no signal — nothing in
 * Vaier knew, so nothing could tell them. This is that signal.
 *
 * <p><b>Daily, not every scrape.</b> Registry manifest requests are rate-limited (anonymous Docker Hub allows
 * roughly 100 per six hours) and an image going stale is not news that decays in thirty seconds. The container
 * scrape stays on its 30s tick and never touches a registry.
 *
 * <p>The watcher decides nothing. {@link ImageUpdateTracker} — the domain — reports which images have
 * <em>just</em> become out of date, and this class only decides whom to tell: exactly the pattern of
 * {@link RemoteDiskWatcher} and {@link BackupServerWatcher}. Nothing changed means no mail, and one sweep
 * finding three stale images sends one rollup rather than three.
 *
 * <p><b>Vaier never pulls.</b> Detection is read-only, always; the operator's move is the operator's.
 */
@Component
@Slf4j
public class ImageUpdateWatcher {

    /**
     * Once a day. The first sweep waits two minutes so the container scrape has a snapshot to judge — sweeping
     * an empty snapshot would decide nothing anyway, but it would waste the day's first look.
     */
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long INITIAL_DELAY_MS = 2 * 60 * 1000L;

    private final SweepImageUpdatesUseCase sweep;
    private final NotifyAdminsOfUpdateAvailableUseCase notifier;

    /**
     * Injected rather than owned since #57 slice 3: the operator's own update check clears an image's alert
     * state once it confirms a pull, so the check and this watcher must share one memory. See
     * {@code ImageUpdateConfig} for what two instances would cost.
     */
    private final ImageUpdateTracker tracker;

    public ImageUpdateWatcher(SweepImageUpdatesUseCase sweep, NotifyAdminsOfUpdateAvailableUseCase notifier,
                              ImageUpdateTracker tracker) {
        this.sweep = sweep;
        this.notifier = notifier;
        this.tracker = tracker;
    }

    @Scheduled(fixedDelay = ONE_DAY_MS, initialDelay = INITIAL_DELAY_MS)
    public void checkForImageUpdates() {
        try {
            Map<String, UpdateAvailability> verdicts = sweep.sweepImageUpdates();
            List<String> newlyOutOfDate = tracker.update(verdicts);

            ImageUpdateRollup rollup = new ImageUpdateRollup(newlyOutOfDate);
            if (rollup.worthSending()) {
                notifier.notifyAdminsOfUpdateAvailable(rollup);
            }
        } catch (Exception e) {
            // A dead Docker host, a dead registry, a dead SMTP server: none of them may kill the schedule.
            log.debug("Update-available sweep failed: {}", e.getMessage());
        }
    }
}
