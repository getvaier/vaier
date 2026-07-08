package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A short per-machine history of {@code df} readings, kept only to project the disk-fill trend. It is a
 * ring buffer capped at {@link #MAX_SAMPLES}: each new reading is appended and the oldest is evicted, so
 * the forecast always reflects the recent past rather than the whole uptime.
 *
 * <p>{@link #forecast(String)} fits a line through the retained samples by least squares — not a
 * two-point delta, so a single noisy reading can't dominate — and turns its slope into a
 * {@link DiskFillForecast}. It returns empty (nothing to warn about) when there is too little signal:
 * fewer than {@link #MIN_SAMPLES} samples, a span under {@link #MIN_SPAN}, or a slope that is flat or
 * draining (a filesystem that isn't filling has an infinite runway). All of that is a domain decision —
 * the watcher only feeds readings in and reads the forecast out.
 */
public class DiskFillHistory {

    /** Ring-buffer capacity: at a 5-minute cadence this is roughly the last hour of readings. */
    static final int MAX_SAMPLES = 12;

    /** Fewer than this and there is too little to trust a trend. */
    static final int MIN_SAMPLES = 3;

    /** Below this span the samples are too bunched in time to project a rate. */
    static final Duration MIN_SPAN = Duration.ofMinutes(15);

    private record Sample(Instant at, int percent) { }

    private final Deque<Sample> samples = new ArrayDeque<>();

    /** Append a reading, evicting the oldest once the buffer is full. */
    public synchronized void record(Instant at, int usedPercent) {
        samples.addLast(new Sample(at, usedPercent));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /**
     * Project a {@link DiskFillForecast} from the retained samples, or empty when there is nothing worth
     * warning about (too few samples, too short a span, or a flat/draining slope).
     */
    public synchronized Optional<DiskFillForecast> forecast(String machineName) {
        List<Sample> snapshot = new ArrayList<>(samples);
        if (snapshot.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        Instant first = snapshot.get(0).at();
        Instant last = snapshot.get(snapshot.size() - 1).at();
        if (Duration.between(first, last).compareTo(MIN_SPAN) < 0) {
            return Optional.empty();
        }

        // Least-squares slope of percent (y) against hours-since-first (x). Origin at `first` keeps the
        // x values small; the slope is invariant to that choice.
        double n = snapshot.size();
        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumXY = 0;
        for (Sample s : snapshot) {
            double x = Duration.between(first, s.at()).toMillis() / 3_600_000.0;
            double y = s.percent();
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) {
            return Optional.empty();
        }
        double slopePercentPerHour = (n * sumXY - sumX * sumY) / denominator;
        if (slopePercentPerHour <= 0) {
            return Optional.empty();
        }

        int currentPercent = snapshot.get(snapshot.size() - 1).percent();
        double runwayHours = (100.0 - currentPercent) / slopePercentPerHour;
        Duration runway = Duration.ofSeconds(Math.round(runwayHours * 3600.0));
        return Optional.of(new DiskFillForecast(machineName, currentPercent, slopePercentPerHour, runway));
    }
}
