package net.vaier.application;

import net.vaier.domain.BackupServer;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;

public interface NotifyAdminsOfBackupServerDownUseCase {

    /**
     * Alert admins that a Backup server has gone down — sent once when it crosses from healthy to down.
     * The {@code cause} (REFUSED vs UNREACHABLE) is carried through so the email can say whether it is the
     * borg container or the whole host that vanished.
     */
    void notifyAdminsOfBackupServerDown(BackupServer server, ProbeResult cause);

    /** Tell admins a previously down Backup server is reachable again — the all-clear. */
    void notifyAdminsOfBackupServerRecovered(BackupServer server);
}
