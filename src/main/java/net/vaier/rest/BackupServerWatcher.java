package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfBackupServerDownUseCase;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BackupServerHealthTracker;
import net.vaier.domain.Machine;
import net.vaier.domain.port.ForProbingTcp;
import net.vaier.domain.port.ForProbingTcp.ProbeResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Sweeps every configured {@link BackupServer} and emails admins when one crosses from healthy to down (or
 * back). Vaier owns fleet backup, so a vanished Backup server deserves its own alert rather than surfacing
 * only as per-job failures at 02:00. It TCP-probes {@code host:sshPort} via {@link ForProbingTcp} (the same
 * port {@code LanServerReachabilityService} uses — no new probe port) and a per-server
 * {@link BackupServerHealthTracker} makes it alert only on boundary crossings, with two-strike hysteresis so
 * a single blip never pages and a restart never re-pages an already-up server.
 *
 * <p>The probe result is carried into the alert so the operator knows what broke: {@code REFUSED} (host alive,
 * nothing listening) reads as "the borg server container is down"; {@code UNREACHABLE} (timeout / low-level
 * error) reads as "the host is unreachable".
 *
 * <p><b>What this watch does and does not answer.</b> It probes from <em>Vaier</em>, so it answers
 * "is the backup server up?" — <em>not</em> "can backup jobs reach it?". A backup client on another LAN can
 * fail to route to a server that Vaier sees perfectly well (this asymmetry was the live Colina routing bug).
 * The per-job {@code checkNas} probe, which runs from the client host, remains authoritative for
 * "can jobs reach it?"; the two must not be conflated.
 */
@Component
@Slf4j
public class BackupServerWatcher {

    /**
     * Sweep every 5 minutes, matching {@code RemoteDiskWatcher}'s cadence. A Backup server is not
     * latency-critical — jobs run nightly — so combined with two-strike hysteresis this pages roughly ten
     * minutes after a server goes down, trading a slightly slower page for immunity to transient blips while
     * keeping the TCP-probe load on the fleet negligible.
     */
    private static final long SWEEP_INTERVAL_MS = 300000;

    /** Bounded so a slow/unreachable host cannot stall the sweep; generous enough for a probe over the VPN. */
    private static final int PROBE_TIMEOUT_MS = 2000;

    private final GetBackupServersUseCase backupServers;
    private final GetMachinesUseCase machines;
    private final ForProbingTcp probe;
    private final NotifyAdminsOfBackupServerDownUseCase notifier;
    private final BackupServerHealthTracker tracker = new BackupServerHealthTracker();

    public BackupServerWatcher(GetBackupServersUseCase backupServers,
                               GetMachinesUseCase machines,
                               ForProbingTcp probe,
                               NotifyAdminsOfBackupServerDownUseCase notifier) {
        this.backupServers = backupServers;
        this.machines = machines;
        this.probe = probe;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void checkBackupServers() {
        for (BackupServer server : backupServers.getBackupServers()) {
            checkServer(server);
        }
    }

    private void checkServer(BackupServer server) {
        ProbeResult result;
        try {
            result = probe.probe(server.host(), server.sshPort(), PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            // A failed probe must never masquerade as a real event: leave the tracker untouched and move on.
            log.debug("TCP probe of backup server {} failed: {}", LogSafe.forLog(server.name()), e.getMessage());
            return;
        }
        boolean healthy = result == ProbeResult.CONNECTED;
        // The machine label is resolved INSIDE the notify lambdas, not before the switch: it is only needed
        // when an email is actually being sent, and reading the machine registry means shelling into the
        // WireGuard container. Doing it eagerly cost that on every quiet sweep and — because it sits
        // outside notifyQuietly's guard — let a restarting container take the whole sweep down.
        switch (tracker.update(server.name(), healthy)) {
            case CROSSED_TO_DOWN -> {
                // Switched off on purpose: going down was expected, and so is coming back — say neither.
                if (switchedOffOnPurpose(server)) {
                    tracker.wentDownQuietly(server.name());
                    return;
                }
                notifyQuietly(
                    () -> notifier.notifyAdminsOfBackupServerDown(server, machineLabelFor(server), result),
                    "down alert for backup server " + server.name());
            }
            case CROSSED_TO_HEALTHY -> notifyQuietly(
                () -> notifier.notifyAdminsOfBackupServerRecovered(server, machineLabelFor(server)),
                "recovery alert for backup server " + server.name());
            case NONE -> { /* no boundary crossed; stay quiet */ }
        }
    }

    /**
     * What to call the server's machine in an email. The server holds its machine's identity, which is the
     * right key and an unreadable thing to put in an inbox — so the name is resolved here, at the driving
     * edge, and what to say when there is no name to resolve is {@link Machine#labelFor}'s decision, shared
     * with every other thing a person reads.
     *
     * <p>Never throws. The registry reads WireGuard by shelling into a container, so it fails whenever that
     * container is restarting — an ordinary state of a fleet. Losing a <em>down alert</em> because the
     * cosmetic step that names its machine failed would be absurd, so an unreadable registry degrades to the
     * same label a departed machine gets and the email goes out regardless.
     */
    private String machineLabelFor(BackupServer server) {
        Optional<String> name;
        try {
            name = machines.getAllMachines().stream()
                .filter(m -> m.id().equals(server.machineId()))
                .map(Machine::name)
                .findFirst();
        } catch (Exception e) {
            log.debug("Could not resolve the name of backup server {}'s machine: {}",
                LogSafe.forLog(server.name()), e.getMessage());
            name = Optional.empty();
        }
        return Machine.labelFor(server.machineId(), name);
    }

    /** Read only on a crossing, like the label; an unreadable registry is not a reason to stay quiet. */
    private boolean switchedOffOnPurpose(BackupServer server) {
        try {
            return machines.getAllMachines().stream()
                .filter(m -> m.id().isSameAs(server.machineId()))
                .anyMatch(Machine::isSwitchedOffOnPurpose);
        } catch (Exception e) {
            return false;
        }
    }

    /** Run a notification, swallowing any failure so one bad send can never break the rest of the sweep. */
    private void notifyQuietly(Runnable send, String context) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("Failed to send {}: {}", context, e.getMessage());
        }
    }
}
