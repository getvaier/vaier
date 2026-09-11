package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AuditReverseProxyConfigUseCase;
import net.vaier.application.DetectMachineNetworksUseCase;
import net.vaier.application.ForgetMachineNetworksUseCase;
import net.vaier.application.GetClaudeSignInStatusUseCase;
import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfMissingDefaultRouteUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.NotifyAdminsOfReverseProxyFindingsUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.MissingDefaultRouteTracker;
import net.vaier.domain.NoSshServerException;
import net.vaier.domain.RemoteDiskForecastTracker;
import net.vaier.domain.RemoteDiskPressureTracker;
import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.ReverseProxyAuditTracker;
import net.vaier.domain.SshServerPresence;
import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import net.vaier.domain.port.ForPersistingDiskFillTrends;
import net.vaier.domain.port.ForPersistingDiskPressureState;
import net.vaier.domain.port.ForPersistingMissingDefaultRoutes;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRecordingDockerCommandAccess;
import net.vaier.domain.port.ForRecordingSshServerPresence;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <b>The fleet's five-minute rounds.</b> Its name says disks because disks are what it was built for and
 * still its largest job, but what it really is now is <em>the one trip Vaier makes to every credentialed
 * machine</em>, and five things are learned on it: each machine's filesystems (and the alerts they raise),
 * its {@link SshServerPresence}, the network it sits on, its {@link DockerCommandAccess}
 * (#352), and where it stands on {@link net.vaier.domain.ClaudeSignIn Claude sign-in}. New facts belong
 * here rather than in a sweep of their own precisely because the sign-in has already been paid for; the
 * name is the thing that has fallen behind, and renaming it is worth its own change rather than riding
 * along with a behaviour one.
 *
 * <p>Polls <b>every real filesystem</b> of every SSH-accessible machine that Vaier holds a credential for
 * and emails admins when a watched one crosses its alert threshold. This covers the Vaier host itself (via
 * SSH-to-self) as well as every other machine, so there is a single disk-alert path for the whole fleet. It
 * runs a bounded {@code df} over SSH via {@link RunRemoteCommandUseCase} (never touching the SSH ports
 * directly), and a per-filesystem {@link RemoteDiskPressureTracker} decides what admins hear: the first
 * time a filesystem is ever seen in pressure, again each time it climbs into a higher
 * {@link net.vaier.domain.DiskPressureBand}, and once more when it drops back to normal. Nothing is
 * re-alerted every poll, and nothing is re-alerted on a timer either.
 *
 * <p><b>The silence this watcher was fixed for.</b> The Vaier server's own root filesystem reached 89%
 * against an 80% threshold and not one email went out. The tracker's state was a boolean latch on a field
 * of this very class: a redeploy — several a day here — wiped it, so every sweep was a "first observation",
 * and a first observation was treated as a silent baseline. A filesystem that was already above its
 * threshold could therefore never cross it, forever. The state now lives behind
 * {@link ForPersistingDiskPressureState} and the first observation speaks. Suppression is logged, so a
 * quiet disk is never again indistinguishable from an unwatched one.
 *
 * <p><b>#325.</b> It used to read {@code df -P /} — the root filesystem, and only the root filesystem. On the
 * NAS that is the fixed-size 2.3 GB DSM system partition, 88% by design and never moving, so the alert Vaier
 * could send was about a partition the operator could not act on, while {@code /volume1} — 11.6 TB, holding
 * every borg backup — was invisible and could have filled to 100% without a word. Now every filesystem is
 * read, each is judged against its own {@link DiskWatch} (watched or muted, at its own threshold or the
 * global one), and the alert <em>names the mount and its size</em>: "NAS /volume1 is at 91% (10.8 TiB, 1.0
 * TiB free)" tells the operator something they can act on; "NAS is at 88%" told them nothing.
 *
 * <p>Machines without SSH access or without a stored credential are skipped silently, so Vaier never mounts a
 * failed-auth storm. A machine that is unreachable, whose {@code df} times out or exits non-zero, or returns
 * output that cannot be parsed is degraded, not alarmed — the trackers are left untouched so a transient
 * failure can never masquerade as a full disk.
 *
 * <p>The same readings feed a second, forward-looking consumer: a per-filesystem
 * {@link RemoteDiskForecastTracker} projects the disk-fill <b>runway</b> from a week of retained free-space
 * samples and emails an early warning when a filesystem is projected to fill within the forecast horizon
 * <em>while still below</em> its threshold — so a filling disk pages once as a forecast and then the level
 * alert takes over. Its samples and its already-warned latch live behind
 * {@link ForPersistingDiskFillTrends}, because a week of baseline cannot accumulate in a field wiped by
 * every redeploy. No extra SSH round-trip: the forecast reads the readings the level check already took, and
 * a failed or unparseable {@code df} records no sample (the failure paths {@code return} before the forecast
 * feed).
 *
 * <p>A third consumer rides the same SSH attempt: whether the machine has an SSH server listening at all.
 * A refused connect surfaces from the shared {@code SshConnector} as {@link NoSshServerException} — the one
 * failure narrow enough to record with confidence, since a timeout or an auth failure could just as easily
 * mean the machine is merely asleep. Recorded via {@link ForRecordingSshServerPresence} and, on a boundary
 * crossing, published on the {@code vpn-peers} SSE stream so the Explorer can grey out SSH-dependent
 * controls (and lift the greying the moment a later sweep reaches the machine again) without waiting for an
 * operator's click to fail. No extra SSH traffic: this reads the very attempt {@code checkMachine} already
 * makes.
 *
 * <p>A fourth consumer rides the same sweep (#333): the <b>network the machine is on</b>, read via
 * {@link DetectMachineNetworksUseCase} and cached, so the Explorer can offer to route a relay's LAN
 * instead of asking an operator to type a CIDR. Be honest about the cost — unlike the forecast and the
 * SSH-server presence, this one is a second {@code exec} on the same machine, because it is a different
 * question and {@code df} cannot answer it. What it is <em>not</em> is a per-page-paint cost: the nudges
 * endpoint repaints every time a machine pane is opened, and every other signal it composes is already
 * free. Detecting here and serving from cache is what keeps it that way, and it self-heals — a machine
 * that moves house is re-read within five minutes.
 *
 * <p>That same reading answers a second question for free (#357): whether the machine has <b>a default
 * route at all</b>. Everything else Vaier asks a machine is answerable from inside its own LAN, so one that
 * has lost its way out keeps passing every probe here while being unable to pull an image. A
 * {@link MissingDefaultRouteTracker} decides what admins hear — once when it goes, once when it comes back,
 * never on a timer — and its latch is persisted for the same reason the pressure tracker's is.
 *
 * <p>Both extra consumers sit behind {@code checkMachine}'s two existing guards (SSH access, a stored
 * credential), which is precisely why "a machine Vaier cannot read is simply not nudged" needs no rule of
 * its own.
 *
 * <p><b>And one rider that asks no machine anything (#354):</b> the <b>reverse proxy audit</b>, Vaier reading back
 * the Traefik config it writes itself and judging it against invariants no route can reach. It belongs on
 * this sweep for the reason the others do — the trip already happens — and not behind the per-machine
 * guards, because it is a local file read about Vaier's own config rather than a question for the fleet. It
 * reports and never repairs: see {@link net.vaier.domain.ReverseProxyAudit}.
 */
@Component
@Slf4j
public class RemoteDiskWatcher {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    private final NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    private final GetDiskWatchesUseCase diskWatches;
    private final ConfigResolver configResolver;
    private final Clock clock;
    private final ForRecordingSshServerPresence sshPresenceRecorder;
    private final ForPublishingEvents eventPublisher;
    private final DetectMachineNetworksUseCase detectMachineNetworks;
    private final ForgetMachineNetworksUseCase forgetMachineNetworks;
    private final ForHoldingMachineDiskStandings diskStandings;
    // A second fact this same trip can teach, and the only place Vaier can learn it: the container scrape
    // reads Docker's API over the tunnel and never needs the docker group this asks about.
    private final ForRecordingDockerCommandAccess dockerAccessRecorder;
    private final ForPersistingDiskFillTrends diskFillTrends;
    // The fifth fact, asked through the very same use case a machine's own pane asks — so there is one
    // path to a sign-in answer and no second one that could report a state the CLI never said.
    private final GetClaudeSignInStatusUseCase claudeSignIn;
    private final ForHoldingClaudeSignInStandings claudeStandings;
    // The one thing on this sweep that asks no machine anything (#354): Vaier reading back the routing
    // config it writes itself. It rides here because a local file read costs nothing next to a fleet of SSH
    // sessions, and because a config can rot between restarts — Vaier's are rare.
    private final AuditReverseProxyConfigUseCase reverseProxyAudit;
    private final NotifyAdminsOfReverseProxyFindingsUseCase reverseProxyAuditNotifier;
    private final NotifyAdminsOfMissingDefaultRouteUseCase missingRouteNotifier;
    private final RemoteDiskPressureTracker tracker;
    private final MissingDefaultRouteTracker missingRouteTracker;
    private final RemoteDiskForecastTracker forecastTracker;

    // The stream the Explorer already holds open for fleet liveness (peers, LAN reachability) — piggybacking
    // here costs no new connection and no timer.
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSH_SERVER_PRESENCE_SSE_EVENT = "ssh-server-presence-changed";

    public RemoteDiskWatcher(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             NotifyAdminsOfRemoteDiskPressureUseCase notifier,
                             NotifyAdminsOfDiskFillForecastUseCase forecastNotifier,
                             GetDiskWatchesUseCase diskWatches,
                             ConfigResolver configResolver,
                             Clock clock,
                             ForRecordingSshServerPresence sshPresenceRecorder,
                             ForPublishingEvents eventPublisher,
                             ForPersistingDiskPressureState diskPressureState,
                             DetectMachineNetworksUseCase detectMachineNetworks,
                             ForgetMachineNetworksUseCase forgetMachineNetworks,
                             ForHoldingMachineDiskStandings diskStandings,
                             ForRecordingDockerCommandAccess dockerAccessRecorder,
                             ForPersistingDiskFillTrends diskFillTrends,
                             GetClaudeSignInStatusUseCase claudeSignIn,
                             ForHoldingClaudeSignInStandings claudeStandings,
                             AuditReverseProxyConfigUseCase reverseProxyAudit,
                             NotifyAdminsOfReverseProxyFindingsUseCase reverseProxyAuditNotifier,
                             ForPersistingMissingDefaultRoutes missingDefaultRoutes,
                             NotifyAdminsOfMissingDefaultRouteUseCase missingRouteNotifier) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.notifier = notifier;
        this.forecastNotifier = forecastNotifier;
        this.diskWatches = diskWatches;
        this.configResolver = configResolver;
        this.clock = clock;
        this.sshPresenceRecorder = sshPresenceRecorder;
        this.eventPublisher = eventPublisher;
        this.detectMachineNetworks = detectMachineNetworks;
        this.forgetMachineNetworks = forgetMachineNetworks;
        this.diskStandings = diskStandings;
        this.dockerAccessRecorder = dockerAccessRecorder;
        this.diskFillTrends = diskFillTrends;
        this.claudeSignIn = claudeSignIn;
        this.claudeStandings = claudeStandings;
        this.reverseProxyAudit = reverseProxyAudit;
        this.reverseProxyAuditNotifier = reverseProxyAuditNotifier;
        this.missingRouteNotifier = missingRouteNotifier;
        // The domain owns the port call; this watcher only hands it in. Both trackers' state is on disk
        // precisely so that a redeploy — several a day here — no longer wipes what admins have already been
        // told, nor the week of samples the forecast projects from.
        this.tracker = new RemoteDiskPressureTracker(diskPressureState);
        this.forecastTracker = new RemoteDiskForecastTracker(diskFillTrends);
        this.missingRouteTracker = new MissingDefaultRouteTracker(missingDefaultRoutes);
    }

    @Scheduled(fixedDelay = 300000)
    public void checkRemoteDiskUsage() {
        int globalThreshold = configResolver.getDiskMonitorThresholdPercent();
        DiskWatches watches = diskWatches.getDiskWatches();
        List<Machine> allMachines = machines.getAllMachines();
        for (Machine machine : allMachines) {
            checkMachine(machine, watches, globalThreshold);
        }
        // A machine deleted while Vaier was running must not leave its SSH-server presence — or the network
        // it was last seen on — behind forever.
        Set<MachineId> fleet = allMachines.stream().map(Machine::id).collect(Collectors.toSet());
        sshPresenceRecorder.retainOnly(fleet);
        dockerAccessRecorder.retainOnly(fleet);
        diskStandings.retainOnly(fleet);
        claudeStandings.retainOnly(fleet);
        diskFillTrends.retainOnly(fleet);
        forgetMachineNetworks.forgetMachineNetworksExcept(fleet);
        auditReverseProxyConfig();
    }

    /**
     * Vaier judging the reverse proxy config it writes itself (#354), once per sweep.
     *
     * <p>Every other check on this trip is about the world outside — is the host up, is the disk filling.
     * None of them asked <em>is the config I wrote still coherent?</em>, so a defect in a write path stayed
     * invisible until an operator opened the one URL that happened to break: four orphaned redirect
     * middlewares, three of them redirect loops, sitting in the file for weeks.
     *
     * <p>In its own try/catch, for the reason every other rider here is: a file read that fails must never
     * be able to take a fleet-wide disk sweep down with it. And the decision is not taken here — the domain
     * decides what is broken and whether it is news; this only decides whom to tell.
     */
    private void auditReverseProxyConfig() {
        try {
            ReverseProxyAuditTracker.Verdict verdict = reverseProxyAudit.auditReverseProxyConfig();
            switch (verdict.outcome()) {
                case ALERT -> reverseProxyAuditNotifier
                    .notifyAdminsOfReverseProxyFindings(verdict.audit());
                case RECOVERED -> reverseProxyAuditNotifier
                    .notifyAdminsOfReverseProxyAuditRecovery(verdict.audit());
                // Clear, or in exactly the trouble admins already know about. Logged when there is trouble,
                // so a config sitting broken is never indistinguishable from one nobody is checking.
                case QUIET -> {
                    if (!verdict.audit().isClean()) {
                        log.info("Vaier's reverse proxy config still has {} entries no route can reach; admins "
                            + "have already been told", verdict.audit().findings().size());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not audit the reverse proxy config: {}", e.getMessage());
        }
    }

    /**
     * One machine, behind the two guards that decide whether Vaier may reach it at all. Both consumers of
     * this trip sit behind them, and that is what makes the acceptance criterion of #333 — "a machine Vaier
     * cannot read is simply not nudged" — true without a rule of its own.
     */
    private void checkMachine(Machine machine, DiskWatches watches, int globalThreshold) {
        if (!machine.effectiveSshAccess()) {
            return;
        }
        if (credentials.getHostCredential(machine.id()).isEmpty()) {
            return;
        }
        checkDisks(machine, watches, globalThreshold);
        detectNetworks(machine);
        readClaudeSignIn(machine);
    }

    /**
     * Reads where this machine stands on Claude sign-in and retains it, so the Explorer's fleet cards can
     * wear it without any of them asking a machine anything.
     *
     * <p>Behind the same two guards as everything else on this trip, so the standing exists for exactly the
     * machines Vaier can actually reach — a phone, or a machine with no stored login, is never asked and
     * never marked.
     *
     * <p>In its own try/catch for the same reason {@link #detectNetworks} is: a fifth question on this trip
     * must never be able to take the first four down with it, and must not reach the
     * {@link NoSshServerException} handler and withdraw an SSH-server presence {@code df} has already
     * earned. A read that failed retains nothing at all — the domain records only what it was actually
     * told.
     */
    private void readClaudeSignIn(Machine machine) {
        ClaudeSignInStatus status = null;
        try {
            status = claudeSignIn.getClaudeSignInStatus(machine.id());
        } catch (Exception e) {
            log.debug("Could not read where {} stands on Claude: {}", machine.name(), e.getMessage());
        }
        ClaudeSignInStatus.retain(status, claudeStandings, eventPublisher);
    }

    /**
     * Reads the network the machine is on and caches it (#333), so the Explorer can offer to route it
     * without ever reaching the machine itself.
     *
     * <p>In its own try/catch, deliberately. It is a second question on the same sweep, not a second half
     * of the first: a network read that fails must not cost the machine its disk alerts, and — the subtler
     * one — must not reach the {@link NoSshServerException} handler above and withdraw an SSH-server
     * presence that {@code df} has already earned on this very trip.
     */
    private void detectNetworks(Machine machine) {
        MachineNetworks detected = null;
        try {
            detected = detectMachineNetworks.detectMachineNetworks(machine.id());
        } catch (Exception e) {
            log.debug("Could not read the networks on {}: {}", machine.name(), e.getMessage());
        }
        judgeDefaultRoute(machine, detected == null ? MachineNetworks.unknown() : detected);
    }

    /**
     * The second thing that same reading says (#357): whether the machine can reach the internet at all.
     * Everything else on this trip is answerable from inside the machine's own LAN — it is reached through
     * its relay peer, its disks read over that path, its backups go to a backup server on the same LAN — so
     * a machine that has lost its default route passes every probe and reads as fully healthy. The domain
     * owns the latch and the transition rule; this only decides whom to tell.
     *
     * <p>Its own try/catch, so a mail that will not go out can never take the sweep down with it.
     */
    private void judgeDefaultRoute(Machine machine, MachineNetworks networks) {
        try {
            switch (missingRouteTracker.observe(machine.id(), networks.defaultRoute())) {
                case ALERT -> missingRouteNotifier
                    .notifyAdminsOfMissingDefaultRoute(machine.name(), networks);
                case RECOVERED -> missingRouteNotifier
                    .notifyAdminsOfDefaultRouteRestored(machine.name(), networks);
                case QUIET -> { }
            }
        } catch (Exception e) {
            log.warn("Could not tell admins where {} stands on its default route: {}", machine.name(),
                e.getMessage());
        }
    }

    private void checkDisks(Machine machine, DiskWatches watches, int globalThreshold) {
        try {
            // Two questions, one sign-in. The Docker probe rides in FRONT of df and prints its exit status
            // on a marker line df's parser cannot mistake for a filesystem, so the disk reading below is
            // exactly what it was — same stdout, same exit code, same failure paths.
            CommandResult result =
                remoteCommand.run(machine.id(), DockerCommandAccess.probeAheadOf(RemoteDiskUsage.DF_COMMAND));
            // Reaching a CommandResult at all — whatever df itself said — already proves the SSH session
            // connected and authenticated, so the server is present regardless of df's own exit status.
            recordSshServerPresent(machine);
            // Recorded before df's own exit status is judged: whether Vaier can drive Docker there is a fact
            // this trip established even when df itself had nothing useful to say. The domain decides
            // whether anything was learned at all, and a trip that learned nothing records nothing.
            DockerCommandAccess.retain(machine.id(), result, dockerAccessRecorder);
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("Remote df on {} failed (exit={}, timedOut={}); skipping",
                        machine.name(), result.exitCode(), result.timedOut());
                return;
            }
            List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList(machine.name(), result.stdout());
            if (filesystems.isEmpty()) {
                log.debug("Could not parse remote df output from {}; skipping", machine.name());
                return;
            }
            for (RemoteDiskUsage filesystem : filesystems) {
                checkFilesystem(machine, filesystem, watches, globalThreshold);
            }
            recordDiskStanding(machine, filesystems, watches, globalThreshold);
        } catch (NoSshServerException e) {
            // The one failure narrow enough to record with confidence — see NoSshServerException.
            recordSshServerAbsent(machine);
        } catch (Exception e) {
            // Every other failure (timeout, auth, host-key mismatch, an unreachable network) is ambiguous —
            // it could just as easily mean the machine is asleep — so it must never move the tracker either
            // way.
            log.debug("Remote disk check failed for {}: {}", machine.name(), e.getMessage());
        }
    }

    /**
     * <b>Retains what this sweep already read</b> as the machine's {@link MachineDiskStanding}, so the
     * Explorer's fleet listing can mark every card with its disk pressure without asking a single machine
     * anything. Until now these readings were judged, emailed about where they warranted it, and then
     * dropped on the floor — which is why the fleet listing had no disk ambience and could not have grown
     * any without a fleet-wide {@code df} on page load, waking every sleeping machine to answer a question
     * nobody asked.
     *
     * <p>The watcher decides nothing here — it hands the reading and the two ports to
     * {@link MachineDiskStanding#retain}, which picks the worst filesystem through the same
     * {@code RemoteDiskUsage.judge} the alert email above just used, forgets a machine with nothing left to
     * judge, and wakes an open Explorer only when something actually moved. The disk pane hands its own live
     * reading to that very same method, which is what makes a mute land on the card at once instead of
     * waiting for the next sweep.
     *
     * <p>Published only on a change, and on the {@code vpn-peers} stream the fleet page already holds open —
     * the same shape as the SSH-server presence above, for the same reason: no second connection, no timer,
     * and no five-minute drumbeat for disks that are sitting still.
     */
    private void recordDiskStanding(Machine machine, List<RemoteDiskUsage> filesystems, DiskWatches watches,
                                    int globalThreshold) {
        MachineDiskStanding.retain(machine.id(), filesystems, watches, globalThreshold, diskStandings,
            eventPublisher);
    }

    /** Records that the SSH server is gone and, only on the boundary crossing, wakes the Explorer. */
    private void recordSshServerAbsent(Machine machine) {
        SshServerPresence previous = sshPresenceRecorder.record(machine.id(), SshServerPresence.ABSENT);
        log.debug("No SSH server detected on {} ({})", machine.name(), machine.id());
        if (previous != SshServerPresence.ABSENT) {
            eventPublisher.publish(SSE_TOPIC, SSH_SERVER_PRESENCE_SSE_EVENT, "");
        }
    }

    /** Records that the SSH server answered and, only when recovering from a known-absent state, wakes it. */
    private void recordSshServerPresent(Machine machine) {
        SshServerPresence previous = sshPresenceRecorder.record(machine.id(), SshServerPresence.PRESENT);
        if (previous == SshServerPresence.ABSENT) {
            eventPublisher.publish(SSE_TOPIC, SSH_SERVER_PRESENCE_SSE_EVENT, "");
        }
    }

    /**
     * One filesystem, judged against its own watch. The watcher decides nothing: it asks the domain once —
     * {@code RemoteDiskUsage.judge} resolves mute, the filesystem's own threshold and the global fallback —
     * and then only decides <em>whom to tell</em>. {@code MachineService.getDiskUsage} asks the very same
     * method to feed the Explorer, which is what guarantees the alert email and the tree can never disagree
     * about a disk.
     *
     * <p>A {@link RemoteDiskUsage.DiskVerdict#silent() silent} filesystem is skipped whole: no alert, and no
     * forecast either. That is the domain's rule, not a convenience here.
     */
    private void checkFilesystem(Machine machine, RemoteDiskUsage filesystem, DiskWatches watches,
                                 int globalThreshold) {
        DiskWatch watch = watches.forFilesystem(machine.id(), filesystem.mountPoint());
        RemoteDiskUsage.DiskVerdict verdict = filesystem.judge(watch, globalThreshold);
        if (verdict.silent()) {
            return;
        }
        int threshold = verdict.thresholdPercent();

        RemoteDiskPressureTracker.Verdict pressure = tracker.observe(machine.id(), filesystem, verdict);
        switch (pressure.outcome()) {
            case ALERT -> notifier.notifyAdminsOfRemoteDiskPressure(filesystem, threshold);
            case RECOVERED -> notifier.notifyAdminsOfRemoteDiskRecovery(filesystem, threshold);
            // Staying quiet has to be visible. The old no-op branch logged nothing at all, so a disk sitting
            // at 89% in total silence looked exactly like a disk nobody was watching — and that is precisely
            // what happened. An operator must be able to tell the two apart from the log alone.
            case SUPPRESSED -> log.info(
                "{} {} is at {}% — above its {}% threshold, already notified at band {}; staying quiet",
                machine.name(), filesystem.mountPoint(), filesystem.usedPercent(), threshold,
                pressure.notifiedBand().map(Object::toString).orElse("none"));
            // Equally visible, for the same reason: a disk in the recovery margin is quiet but not clear.
            case EASING -> log.info(
                "{} {} is at {}% — under its {}% threshold but not by the {}-point recovery margin, still "
                    + "in pressure at band {}; staying quiet",
                machine.name(), filesystem.mountPoint(), filesystem.usedPercent(), threshold,
                RemoteDiskUsage.RECOVERY_MARGIN_PERCENT,
                pressure.notifiedBand().map(Object::toString).orElse("none"));
            case QUIET -> { /* below its threshold and never alerted about; nothing to say */ }
        }

        // Feed the same reading to the trend/forecast consumer (no extra SSH round-trip). The tracker decides
        // what to say: an early warning on the way in, and — only on a genuine recovery below the threshold —
        // an all-clear; a hand-off to the disk-pressure alert above yields neither. The threshold handed in
        // is this filesystem's own, so the forecast hands off at exactly the level the pressure alert fires
        // at.
        RemoteDiskForecastTracker.Observation forecast = forecastTracker.observe(
            machine.id(), filesystem, clock.instant(), threshold);
        forecast.earlyWarning().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecast);
        forecast.cleared().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecastCleared);
    }
}
