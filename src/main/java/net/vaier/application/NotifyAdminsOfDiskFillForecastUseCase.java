package net.vaier.application;

import net.vaier.domain.DiskFillForecast;
import net.vaier.domain.DiskFillForecastCleared;

public interface NotifyAdminsOfDiskFillForecastUseCase {

    /** Warn admins that a machine's disk is projected to fill within the forecast horizon. */
    void notifyAdminsOfDiskFillForecast(DiskFillForecast forecast);

    /**
     * Tell admins a machine's disk-fill early warning has cleared in a genuine recovery (the disk drained
     * or its fill slowed while staying below the disk-pressure threshold). Not sent for a hand-off to the
     * disk-pressure alert — the tracker suppresses that upstream.
     */
    void notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared cleared);
}
