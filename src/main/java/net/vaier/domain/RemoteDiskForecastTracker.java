package net.vaier.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-machine disk-fill-forecast state, the trend-watching sibling of {@link RemoteDiskPressureTracker}
 * (which watches the level). Each machine gets its own {@link DiskFillHistory} feeding the recent readings
 * and its own {@link DiskPressureTracker} keyed on the {@code warrantsEarlyWarning} boolean, so the
 * watcher is told only when a machine <em>crosses</em> into or out of the early-warning condition — never
 * on every poll — and one machine's crossing never disturbs another's. The first observation for a
 * machine is a baseline and produces no transition, so a restart never pages.
 */
public class RemoteDiskForecastTracker {

    private final Map<String, DiskFillHistory> histories = new ConcurrentHashMap<>();
    private final Map<String, DiskPressureTracker> crossings = new ConcurrentHashMap<>();

    /**
     * Record a reading for {@code machineName} and decide what admins should hear. All the decisions —
     * slope, runway, the level-threshold gate, and crucially whether a cleared crossing is a genuine
     * recovery or a hand-off to the disk-pressure alert — live here in the domain; the watcher only sends
     * whichever payload comes back.
     *
     * <p>A crossing <em>into</em> the early-warning condition yields the {@link DiskFillForecast} to warn
     * with. A crossing <em>out</em> is split by why the gate flipped false:
     * <ul>
     *   <li><b>Genuine recovery</b> — the disk is still at/below {@code levelThreshold} (it drained, or its
     *       fill slowed so the runway rose back past the horizon) → a runway-free
     *       {@link DiskFillForecastCleared} all-clear.</li>
     *   <li><b>Hand-off</b> — the disk climbed past {@code levelThreshold} → nothing; the remote-disk-pressure
     *       alert now speaks for it, so raising an all-clear at the same poll would contradict it.</li>
     * </ul>
     */
    public Observation observe(String machineName, Instant at, int usedPercent, int levelThreshold) {
        DiskFillHistory history = histories.computeIfAbsent(machineName, m -> new DiskFillHistory());
        history.record(at, usedPercent);
        Optional<DiskFillForecast> forecast = history.forecast(machineName);
        boolean warrants = forecast.map(f -> f.warrantsEarlyWarning(levelThreshold)).orElse(false);
        DiskPressureTracker.Transition transition = crossings
            .computeIfAbsent(machineName, m -> new DiskPressureTracker())
            .update(warrants);

        Optional<DiskFillForecast> earlyWarning = Optional.empty();
        Optional<DiskFillForecastCleared> cleared = Optional.empty();
        switch (transition) {
            case CROSSED_ABOVE -> earlyWarning = forecast;
            case CROSSED_BELOW -> {
                if (usedPercent <= levelThreshold) {
                    // Genuine recovery: drained, or fill slowed so the runway rose past the horizon.
                    cleared = Optional.of(new DiskFillForecastCleared(machineName, usedPercent));
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
