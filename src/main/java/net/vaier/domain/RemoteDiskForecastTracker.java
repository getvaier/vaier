package net.vaier.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-filesystem disk-fill-forecast state, the trend-watching sibling of {@link RemoteDiskPressureTracker}
 * (which watches the level). Each filesystem gets its own {@link DiskFillHistory} feeding the recent readings
 * and its own {@link DiskPressureTracker} keyed on the {@code warrantsEarlyWarning} boolean, so the watcher
 * is told only when a filesystem <em>crosses</em> into or out of the early-warning condition — never on every
 * poll — and one filesystem's crossing never disturbs another's. The first observation for a filesystem is a
 * baseline and produces no transition, so a restart never pages.
 *
 * <p><b>#325: keyed on machine AND mount point</b>, like {@link RemoteDiskPressureTracker}. Keyed on the
 * machine alone, the samples of every filesystem on a host would land in one history and the least-squares
 * slope would be fitted through a sawtooth of unrelated disks — a flat {@code /} at 88% interleaved with a
 * climbing {@code /volume1} projects nothing meaningful. A runway belongs to a filesystem, not to a host.
 */
public class RemoteDiskForecastTracker {

    private final Map<String, DiskFillHistory> histories = new ConcurrentHashMap<>();
    private final Map<String, DiskPressureTracker> crossings = new ConcurrentHashMap<>();

    /**
     * Record a reading for {@code mountPoint} on {@code machineName} and decide what admins should hear. All
     * the decisions — slope, runway, the level-threshold gate, and crucially whether a cleared crossing is a
     * genuine recovery or a hand-off to the disk-pressure alert — live here in the domain; the watcher only
     * sends whichever payload comes back.
     *
     * <p>{@code levelThreshold} is the threshold <em>this filesystem</em> is judged against — its own when
     * its {@link DiskWatch} carries one, otherwise the global disk alert threshold — already resolved by the
     * caller, so the forecast hands off to the pressure alert at exactly the level the pressure alert fires
     * at. The two can never disagree about where the boundary is.
     *
     * <p>A crossing <em>into</em> the early-warning condition yields the {@link DiskFillForecast} to warn
     * with. A crossing <em>out</em> is split by why the gate flipped false:
     * <ul>
     *   <li><b>Genuine recovery</b> — the filesystem is still at/below {@code levelThreshold} (it drained, or
     *       its fill slowed so the runway rose back past the horizon) → a runway-free
     *       {@link DiskFillForecastCleared} all-clear.</li>
     *   <li><b>Hand-off</b> — it climbed past {@code levelThreshold} → nothing; the remote-disk-pressure alert
     *       now speaks for it, so raising an all-clear at the same poll would contradict it.</li>
     * </ul>
     */
    public Observation observe(String machineName, String mountPoint, Instant at, int usedPercent,
                               int levelThreshold) {
        String key = machineName + '\0' + mountPoint;
        DiskFillHistory history = histories.computeIfAbsent(key, k -> new DiskFillHistory());
        history.record(at, usedPercent);
        Optional<DiskFillForecast> forecast = history.forecast(machineName, mountPoint);
        boolean warrants = forecast.map(f -> f.warrantsEarlyWarning(levelThreshold)).orElse(false);
        DiskPressureTracker.Transition transition = crossings
            .computeIfAbsent(key, k -> new DiskPressureTracker())
            .update(warrants);

        Optional<DiskFillForecast> earlyWarning = Optional.empty();
        Optional<DiskFillForecastCleared> cleared = Optional.empty();
        switch (transition) {
            case CROSSED_ABOVE -> earlyWarning = forecast;
            case CROSSED_BELOW -> {
                if (usedPercent <= levelThreshold) {
                    // Genuine recovery: drained, or fill slowed so the runway rose past the horizon.
                    cleared = Optional.of(new DiskFillForecastCleared(machineName, mountPoint, usedPercent));
                }
                // else: hand-off to the disk-pressure alert — suppress the forecast clear.
            }
            case NONE -> { /* no boundary crossed */ }
        }
        return new Observation(transition, earlyWarning, cleared);
    }

    /**
     * The outcome of an {@link #observe} call: the boundary crossing, plus the ready-to-send payloads —
     * an early warning on a crossing in, an all-clear on a genuine recovery out, both empty otherwise.
     */
    public record Observation(DiskPressureTracker.Transition transition,
                              Optional<DiskFillForecast> earlyWarning,
                              Optional<DiskFillForecastCleared> cleared) { }
}
