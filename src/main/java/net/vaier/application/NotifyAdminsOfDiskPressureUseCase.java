package net.vaier.application;

import net.vaier.domain.DiskUsage;

public interface NotifyAdminsOfDiskPressureUseCase {

    /** Alert admins that the host disk has crossed above the alert threshold. */
    void notifyAdminsOfDiskPressure(DiskUsage usage, int thresholdPercent);

    /** Tell admins the host disk has dropped back below the alert threshold. */
    void notifyAdminsOfDiskRecovery(DiskUsage usage, int thresholdPercent);
}
