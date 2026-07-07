package net.vaier.application;

import net.vaier.domain.RemoteDiskUsage;

public interface NotifyAdminsOfRemoteDiskPressureUseCase {

    /** Alert admins that a remote machine's disk has crossed above the alert threshold. */
    void notifyAdminsOfRemoteDiskPressure(RemoteDiskUsage usage, int thresholdPercent);

    /** Tell admins a remote machine's disk has dropped back below the alert threshold. */
    void notifyAdminsOfRemoteDiskRecovery(RemoteDiskUsage usage, int thresholdPercent);
}
