package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetClaudeSignInStandingsUseCase;
import net.vaier.application.GetContainerStandingsUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachineNetworksUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetSshServerPresenceUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.BackupFleet;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.EffectiveUser;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineContainerStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.MachineNudge;
import net.vaier.domain.MachineNudges;
import net.vaier.domain.MachineSignals;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Reachability;
import net.vaier.domain.SshServerPresence;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/machines")
@RequiredArgsConstructor
@Slf4j
public class MachineRestController {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVaierServerUseCase getVaierServerUseCase;
    private final SetMachineSshAccessUseCase setMachineSshAccessUseCase;
    private final GetHostCredentialUseCase getHostCredentialUseCase;
    private final ClearHostKeyUseCase clearHostKeyUseCase;
    private final GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;
    private final GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    private final GetClaudeSignInStandingsUseCase getClaudeSignInStandingsUseCase;
    private final SetDiskWatchUseCase setDiskWatchUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRunsUseCase getBackupRunsUseCase;
    private final GetBackupServersUseCase getBackupServersUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final GetSshServerPresenceUseCase getSshServerPresenceUseCase;
    private final GetMachineNetworksUseCase getMachineNetworksUseCase;
    private final GetContainerStandingsUseCase getContainerStandingsUseCase;
    // Read for its zone alone: the one a machine's cards write their times in, so the domain never has to
    // ask the environment where the operator is.
    private final Clock clock;

    /**
     * Every machine Vaier knows. Each carries {@code hasCredential} — whether Vaier actually holds an SSH
     * login for it — composed here at the driving edge from {@link GetHostCredentialUseCase}, exactly as the
     * Vaier-server and nudges endpoints do. The Explorer gates a machine's Files and Disk entries on it:
     * those ride on a credential, so the SSH-access toggle alone would grow entries that open onto a "no
     * login" wall.
     *
     * <p>Each also carries {@code sshServerPresence} — Vaier's last-known belief about whether an SSH server
     * is actually listening there, read from {@link GetSshServerPresenceUseCase}. This is the page-load half
     * of the live signal: the {@code ssh-server-presence-changed} event on the {@code vpn-peers} SSE stream
     * carries deltas after the page is open, but a fresh load has no deltas to have received, so the list
     * itself must carry the current state.
     *
     * <p>Each also carries {@code effectiveUsername} and {@code effectiveUserPrivileged} (#346) — the user
     * Vaier acts as there, and whether that user is privileged; the username is null where no credential is
     * stored. It costs no new SSH round trip: the credential's username <em>is</em> the effective user, so
     * the very lookup that answers {@code hasCredential} answers this too, and {@link EffectiveUser} owns
     * the privilege decision so nothing downstream re-derives it from the string "root".
     */
    @GetMapping
    public List<MachineResponse> list() {
        // Which machine Vaier is running on, as an identity. The browser used to work this out by
        // comparing a display name to the literal "Vaier server", which is a name doing an identity's
        // job: rename the host and Vaier stops recognising itself; let another machine take the name
        // and it mistakes that one for itself.
        //
        // Guarded, because identity-keying turns what used to be a string comparison into a lookup, and
        // a lookup can fail — resolving the Vaier server reads config and shells into the WireGuard
        // container. This flag decorates the list; the list itself is the fleet. Losing the whole fleet
        // view because one machine could not be labelled is much the worse failure of the two.
        MachineId vaierServer = resolveVaierServerId();
        return getMachinesUseCase.getAllMachines().stream()
            .map(m -> {
                // One credential lookup per machine, answering both questions it can answer: whether a
                // login is held at all, and which user that login makes Vaier on this machine. Asking
                // twice would re-read the vault off disk for an answer already in hand.
                Optional<HostCredentialView> credential = getHostCredentialUseCase.getHostCredential(m.id());
                return MachineResponse.from(m,
                    hasStoredCredential(credential),
                    m.id().equals(vaierServer),
                    getSshServerPresenceUseCase.getSshServerPresence(m.id()),
                    credential.map(HostCredentialView::username).map(EffectiveUser::of).orElse(null));
            })
            .toList();
    }

    /** The Vaier server's identity, or null when it cannot be resolved right now. Never throws. */
    private MachineId resolveVaierServerId() {
        try {
            Machine server = getVaierServerUseCase.getVaierServerMachine();
            return server == null ? null : server.id();
        } catch (RuntimeException e) {
            log.warn("Could not resolve the Vaier server's identity for the machine list: {}", e.getMessage());
            return null;
        }
    }

    /** Whether a host SSH credential with a secret is stored for this machine. */
    private boolean hasStoredCredential(MachineId machineId) {
        return hasStoredCredential(getHostCredentialUseCase.getHostCredential(machineId));
    }

    /**
     * The same question asked of a credential already in hand — so {@code list()}, which needs the
     * credential anyway for the effective user, does not re-read the vault just to re-ask it. One
     * definition of "Vaier holds a usable login here"; two would be free to drift.
     */
    private static boolean hasStoredCredential(Optional<HostCredentialView> credential) {
        return credential.map(HostCredentialView::hasSecret).orElse(false);
    }

    /**
     * The Vaier server host as a machine (#311): its canonical name, effective SSH access, and
     * whether a host credential is stored for it. Feeds the dedicated Vaier-server card's SSH-access
     * toggle and credential control. Writes reuse {@code /machines/{machineId}/ssh-access} and
     * {@code /machines/{machineId}/ssh-credential} with the returned {@code name}.
     */
    @GetMapping("/vaier-server")
    public VaierServerResponse vaierServer() {
        Machine server = getVaierServerUseCase.getVaierServerMachine();
        return new VaierServerResponse(server.id().value(), server.name(), server.effectiveSshAccess(),
            hasStoredCredential(server.id()));
    }

    record VaierServerResponse(String id, String name, boolean sshAccess, boolean hasCredential) {}

    /**
     * The progressive-adoption nudges Vaier suggests for one machine: evidence-backed, single yes/no
     * prompts to adopt one more capability — publish its exposed services, back it up, make it the
     * fleet's backup server, or (#334) back it up as root once a run has visibly lost files to permissions.
     * Each carries its own "why" from already-cached state.
     *
     * <p><b>Composed at the driving edge.</b> The controller gathers each signal from an existing
     * {@code *UseCase} — the machine, its publishable services, whether a credential is stored, its backup
     * job and latest run, the backup fleet, reachability — and hands them to the pure-domain
     * {@link MachineNudges} assembler, which owns the decisions. No application service reaches across
     * domains to collect nudges, and none implements a driven port to expose them. 404 when no machine
     * has that id. A non-whitelisted path under {@code /machines}, so it is admin-gated automatically.
     */
    @GetMapping("/{machineId}/nudges")
    public List<NudgeResponse> nudges(@PathVariable String machineId) {
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        MachineId id = MachineId.of(machineId);
        Machine target = machines.stream()
            .filter(m -> id.isSameAs(m.id()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineId));

        // Which discovered services sit on THIS machine and are still unanswered. It used to be resolved by
        // name — the owner's name matched against the machine's — which needed a Vaier-server name and an
        // address-to-name map just to do the matching, and answered "a machine called that" where the
        // question was "this machine". The feed carries the owner's identity now, so the domain answers
        // it directly and the two-map scaffolding is gone with the last Machine.hasSameName call.
        //
        // It asks awaitsPublishingOn, not belongsTo: the feed keeps ignored services in it so the Explorer
        // can offer them back under "Show ignored", and counting those re-asked a question the operator had
        // already answered. The domain decides what "still awaiting" means; the edge only counts.
        int publishableCount = (int) getPublishableServicesUseCase.getPublishableServices().stream()
            .filter(s -> s.awaitsPublishingOn(target.id()))
            .count();
        boolean hasCredential = hasStoredCredential(target.id());
        // The machine's job, not a boolean derived from it: "already protected" is one reading of a job, and
        // "should it back up as root" is another, so the edge fetches the job once and judges neither.
        Optional<BackupJob> job = getBackupJobsUseCase.getBackupJobs().stream()
            .filter(j -> target.id().equals(j.machineId()))
            .findFirst();
        // The last run is the evidence behind the back-up-as-root nudge — an archive with holes in it names
        // the files it lost. Read straight from the runs use case, exactly as every other signal here.
        Optional<BackupRun> latestRun = getBackupRunsUseCase.latestForMachine(target.id());
        BackupFleet fleet = new BackupFleet(getBackupServersUseCase.getBackupServers());
        Map<String, Reachability> lanReachability = target.lanAddress() == null ? Map.of()
            : Map.of(target.lanAddress(), getLanServerReachabilityUseCase.getReachability(target.lanAddress()));
        boolean reachable = target.isReachable(lanReachability);
        // #333: the networks the scheduled sweep last read off this machine, and off the Vaier server —
        // the host that would actually install a LAN route, and so the one whose uplink must not be
        // captured. Both come from the cache, so opening a machine pane still costs no SSH round-trip.
        MachineNetworks networks = getMachineNetworksUseCase.getMachineNetworks(target.id());
        MachineId vaierServer = resolveVaierServerId();
        MachineNetworks routingHostNetworks = vaierServer == null ? MachineNetworks.unknown()
            : getMachineNetworksUseCase.getMachineNetworks(vaierServer);

        // #356: where each container Vaier has watched run on THIS machine stands, filtered by identity
        // out of the one fleet-wide read. The domain decides which of them is worth a card.
        List<MachineContainerStanding> containerStandings =
            getContainerStandingsUseCase.getContainerStandings().stream()
                .filter(standing -> target.id().equals(standing.machineId()))
                .toList();

        MachineSignals signals = MachineSignals.builder()
            .publishableCount(publishableCount)
            .reachable(reachable)
            .hasCredential(hasCredential)
            .job(job)
            .latestRun(latestRun)
            .fleet(fleet)
            .networks(networks)
            .routingHostNetworks(routingHostNetworks)
            .containerStandings(containerStandings)
            .zone(clock.getZone())
            .build();

        return MachineNudges.forMachine(target, signals).stream()
            .map(NudgeResponse::from)
            .toList();
    }

    /**
     * One nudge flattened for the browser: its kind, operator-facing title, evidence, action hint, and
     * — for the kinds whose action needs one — the {@code value} being accepted. Today that is the
     * detected CIDR behind {@code ROUTE_LAN}, so the shell can PATCH it straight to the existing
     * {@code /vpn/peers/{peerId}/lan-cidr} endpoint rather than recovering it from the rendered title.
     */
    public record NudgeResponse(String kind, String title, String evidence, String action, String value) {
        static NudgeResponse from(MachineNudge n) {
            return new NudgeResponse(n.kind().name(), n.title(), n.evidence(), n.action(), n.value());
        }
    }

    /**
     * Where every container Vaier has watched run currently stands (#356), for the whole fleet in one
     * request — so the Explorer can mark a container <b>gone</b> rather than merely stopped.
     *
     * <p>It reaches no machine and scrapes nothing. These are the standings the 30-second container scrape
     * has already worked out, retained in memory; per-machine reads would have been N requests for one
     * badge, so this is deliberately one, exactly as {@code /machines/disk-standings} is.
     *
     * <p>A container Vaier has never seen running is simply <b>absent</b>. That absence is the feature: a
     * great many containers are stopped on purpose and forever, and the client's contract is to draw
     * nothing for a container that is not here.
     *
     * <p>A literal path segment under {@code /machines}, so it is admin-gated with the rest of them.
     */
    @GetMapping("/container-standings")
    public List<ContainerStandingResponse> containerStandings() {
        return getContainerStandingsUseCase.getContainerStandings().stream()
            .map(standing -> new ContainerStandingResponse(standing.machineId().value(),
                standing.containerName(), standing.standing().name(),
                standing.lastSeenRunning() == null ? null : standing.lastSeenRunning().toString()))
            .toList();
    }

    /**
     * One container's standing, flattened for the browser.
     *
     * @param machineId       whose container this is — identity, never a name
     * @param containerName   what the container is called on that machine
     * @param standing        the domain's own {@code ContainerStanding}, travelling as a name rather than
     *                        being recomputed in the browser from a state string and a memory it does not
     *                        have. Two surfaces deciding what "gone" means is how they come to disagree.
     * @param lastSeenRunning when Vaier last saw it running, ISO-8601, null when it never has
     */
    record ContainerStandingResponse(String machineId, String containerName, String standing,
                                     String lastSeenRunning) {
    }

    /**
     * Sets whether Vaier offers SSH for a machine — the credential control now, the web terminal
     * later. Writes an explicit override and returns the resulting effective state. 404 (via
     * {@code NotFoundException}) when no machine bears that name. Admin-gated (non-whitelisted path).
     */
    @PatchMapping("/{machineId}/ssh-access")
    public SshAccessResponse setSshAccess(@PathVariable String machineId,
                                          @RequestBody SshAccessRequest request) {
        boolean enabled = request != null && request.enabled();
        boolean effective = setMachineSshAccessUseCase.setMachineSshAccess(MachineId.of(machineId), enabled);
        return new SshAccessResponse(effective);
    }

    /**
     * A machine's filesystems, read now (#323 slice C, fixed by #325). {@code RemoteDiskWatcher} has computed
     * this on a schedule since the disk alerts shipped, but only ever emailed about it — and until #325 it
     * read {@code df -P /}, so it saw the root filesystem and only the root filesystem. On the NAS that is
     * the 2.3 GB DSM system partition (88% by design) while {@code /volume1} — 11.6 TB, every borg backup —
     * was invisible. So this returns <b>every</b> real filesystem.
     *
     * <p>A sibling of {@code /machines/{machineId}/files}: a non-whitelisted path under {@code /machines}, so
     * it sits behind the admin auth chain automatically. Reading a machine's disks is never anonymous.
     *
     * <p>A disk that cannot be read is a {@code DiskUnreadableException} → {@code 502}, carrying the reason
     * verbatim. It is never a {@code 0%} and never an empty list.
     */
    @GetMapping("/{machineId}/disk")
    public List<FilesystemResponse> disk(@PathVariable String machineId) {
        return getMachineDiskUsageUseCase.getDiskUsage(MachineId.of(machineId)).stream()
            .map(fs -> new FilesystemResponse(fs.machineName(), fs.device(), fs.mountPoint(),
                fs.sizeKb(), fs.usedKb(), fs.availableKb(), fs.size(), fs.available(),
                fs.usedPercent(), fs.thresholdPercent(), fs.watched(), fs.aboveThreshold()))
            .toList();
    }

    /**
     * <b>The whole fleet's disk pressure, in one request</b> — what the Explorer's fleet listing marks each
     * machine card with.
     *
     * <p>It reaches no machine. The standings are what {@code RemoteDiskWatcher}'s existing 5-minute sweep
     * already read and now retains in memory, so this is cheap enough to call on page load, which is exactly
     * what {@code /machines/{machineId}/disk} is not: that one runs {@code df} over SSH, and a fleet-wide
     * page-load version of it would wake every sleeping machine to answer a question nobody asked. Per-machine
     * standings would have been N requests for one row of glyphs, so this is deliberately one.
     *
     * <p>A machine the sweep has not reached — a cold start, no SSH access, no stored credential — is simply
     * <b>absent</b> from the list rather than present with a healthy-looking zero. Absence read as health is
     * how a disk at 89% once sat in silence for weeks, and the client's contract is to draw nothing for a
     * machine that is not here.
     *
     * <p>A literal path segment under {@code /machines}, so it is admin-gated with the rest of them. There is
     * no {@code GET /machines/{machineId}} mapping for it to be ambiguous with, and Spring prefers a literal
     * segment over a template regardless.
     */
    @GetMapping("/disk-standings")
    public List<DiskStandingResponse> diskStandings() {
        return getMachineDiskStandingsUseCase.getMachineDiskStandings().stream()
            .map(standing -> new DiskStandingResponse(standing.machineId().value(),
                standing.worstMountPoint(), standing.worstUsedPercent(), standing.worstThresholdPercent(),
                standing.breachingFilesystems(), standing.watchedFilesystems(), standing.level().name()))
            .toList();
    }

    /**
     * One machine's <b>machine disk standing</b>: the worst thing true about its disks right now.
     *
     * <p>{@code level} is the domain's own {@code DiskStandingLevel}, travelling as a name rather than being
     * recomputed in the browser from the percentage and the threshold — the same reason
     * {@link FilesystemResponse#aboveThreshold} travels. Two surfaces deciding when a disk is in trouble is
     * how they come to disagree about it.
     *
     * @param machineId            the machine's identity, never its name
     * @param mountPoint           the mount point of the watched filesystem closest to trouble
     * @param usedPercent          how full that filesystem is
     * @param thresholdPercent     what it is judged against — its own threshold, or the global one
     * @param breachingFilesystems how many of this machine's watched filesystems are over their threshold
     * @param watchedFilesystems   how many filesystems were read and judged (muted ones are neither)
     * @param level                {@code CLEAR}, {@code CLOSING} or {@code BREACHING}
     */
    record DiskStandingResponse(String machineId, String mountPoint, int usedPercent, int thresholdPercent,
                                int breachingFilesystems, int watchedFilesystems, String level) {}

    /**
     * <b>The whole fleet's Claude sign-in standing, in one request</b> — the Claude mark on every machine
     * card, and the sibling of {@link #diskStandings()} in every way that matters.
     *
     * <p>It reaches no machine. The standings are what {@code RemoteDiskWatcher}'s existing five-minute trip
     * already asked each machine's CLI and now retains in memory. That is what makes it safe on page load,
     * and it is exactly what {@code GET /machines/{machineId}/claude-sign-in} is not: that one opens a shell
     * and runs {@code claude auth status}, so a per-card version of it would put a fleet-wide SSH sweep
     * behind opening the Explorer.
     *
     * <p>A machine the sweep has not reached — a cold start, no SSH access, no stored credential — is simply
     * <b>absent</b> from the list. The client's contract is to draw nothing for a machine that is not here,
     * and that contract matters more than it does for disks: {@code ClaudeSignInState} exists partly so that
     * "Vaier couldn't tell" can never be reported as "signed out", and a mark invented from absence would
     * undo it.
     *
     * <p>A literal path segment under {@code /machines}, so it is admin-gated with the rest of them.
     */
    @GetMapping("/claude-standings")
    public List<ClaudeStandingResponse> claudeStandings() {
        return getClaudeSignInStandingsUseCase.getClaudeSignInStandings().stream()
            .map(standing -> new ClaudeStandingResponse(standing.machineId().value(),
                standing.effectiveUsername(), standing.state().name(),
                standing.saysWhereTheSignInStands(),
                standing.account() == null ? null : standing.account().email()))
            .toList();
    }

    /**
     * One machine's <b>Claude sign-in standing</b>: where the OS user Vaier acts as there stands on Claude
     * sign-in, and which account when it is signed in and said so.
     *
     * <p>{@code state} is the domain's own {@code ClaudeSignInState}, travelling as a name — the browser is
     * never a second place deciding what a sign-in reading means, the same reason {@code DiskStandingLevel}
     * travels rather than being recomputed from a percentage.
     *
     * <p>{@code effectiveUsername} is not decoration: a Claude sign-in lives in one user's home directory,
     * so a standing that named only the machine once let Vaier report a healthy account while the user
     * actually running that machine's work was expired.
     *
     * <p>It carries no credential material and could not — every field is read from {@code claude auth
     * status --json}, which emits none.
     *
     * @param machineId         the machine's identity, never its name
     * @param effectiveUsername the OS user the standing is about, or null when Vaier holds no login there
     * @param state             {@code SIGNED_IN}, {@code SIGNED_OUT}, {@code NOT_INSTALLED},
     *                          {@code UNREACHABLE}, {@code SKIPPED} or {@code UNKNOWN}
     * @param saysWhereTheSignInStands whether this reading is a verdict about a sign-in at all, and so
     *                          whether a card marks it — the domain's answer, never re-derived from
     *                          {@code state} in the browser
     * @param accountEmail      the account it is signed in as, or null when there is none to name
     */
    record ClaudeStandingResponse(String machineId, String effectiveUsername, String state,
                                  boolean saysWhereTheSignInStands, String accountEmail) {}

    /**
     * Watch or mute one filesystem on one machine, optionally at its own threshold (#325).
     *
     * <p>The mount point travels in the <b>body</b>, not the path: a mount point contains slashes
     * ({@code /volume1}, and worse), and a path variable carrying them is a routing problem and an encoding
     * bug waiting to happen. In a body a slash is just a character.
     */
    @PutMapping("/{machineId}/disk/watch")
    public DiskWatchResponse setDiskWatch(@PathVariable String machineId,
                                          @RequestBody DiskWatchRequest request) {
        setDiskWatchUseCase.setDiskWatch(MachineId.of(machineId), request.mountPoint(), request.watched(),
            request.thresholdPercent());
        return new DiskWatchResponse(machineId, request.mountPoint(), request.watched(),
            request.thresholdPercent());
    }

    /**
     * One filesystem, with its size, the threshold it was judged against (its own or the global one), whether
     * Vaier watches it, and the domain's verdict on it. The verdict travels rather than the browser
     * recomputing it, so "under pressure" means one thing in the alert email and in the Explorer.
     *
     * <p>The raw {@code *Kb} block counts travel alongside the human-readable {@code size}/{@code available}
     * so a client can sort or graph on them without re-parsing a rendered string.
     */
    record FilesystemResponse(String machine, String device, String mountPoint,
                              long sizeKb, long usedKb, long availableKb,
                              String size, String available,
                              int usedPercent, int thresholdPercent, boolean watched,
                              boolean aboveThreshold) {}

    /** @param thresholdPercent this filesystem's own threshold (1–100), or null to use the global one. */
    record DiskWatchRequest(String mountPoint, boolean watched, Integer thresholdPercent) {}

    /** The watch as it now stands, echoed back so the Explorer can render it without a re-read. */
    record DiskWatchResponse(String machineId, String mountPoint, boolean watched,
                             Integer thresholdPercent) {}

    /**
     * Forget the pinned SSH host key for a machine (#308), so the next terminal connect re-pins on
     * first use. Use after a host is legitimately rebuilt and a host-key mismatch is refusing connects.
     */
    @DeleteMapping("/{machineId}/host-key")
    public ResponseEntity<Void> clearHostKey(@PathVariable String machineId) {
        clearHostKeyUseCase.clearHostKey(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    record SshAccessRequest(boolean enabled) {}

    record SshAccessResponse(boolean sshAccess) {}

    public record MachineResponse(
        String id,
        String name,
        String type,
        String publicKey,
        String allowedIps,
        String endpointIp,
        String endpointPort,
        String latestHandshake,
        String transferRx,
        String transferTx,
        String lanCidr,
        String lanAddress,
        boolean runsDocker,
        Integer dockerPort,
        String deviceCategory,
        boolean sshAccess,
        boolean hasCredential,
        boolean vaierServer,
        SshServerPresence sshServerPresence,
        String effectiveUsername,
        boolean effectiveUserPrivileged,
        boolean canRelayALan,
        boolean acceptsSetupScript
    ) {
        static MachineResponse from(Machine m, boolean hasCredential) {
            return from(m, hasCredential, false, SshServerPresence.UNKNOWN);
        }

        static MachineResponse from(Machine m, boolean hasCredential, boolean vaierServer,
                                    SshServerPresence sshServerPresence) {
            return from(m, hasCredential, vaierServer, sshServerPresence, null);
        }

        /**
         * @param effectiveUser the user Vaier acts as on this machine, or null when it holds no login for
         *                      it. Flattened onto the response: the decision travels as a boolean the
         *                      browser reads, never as a name it re-judges.
         */
        static MachineResponse from(Machine m, boolean hasCredential, boolean vaierServer,
                                    SshServerPresence sshServerPresence, EffectiveUser effectiveUser) {
            return new MachineResponse(
                m.id().value(),
                m.name(),
                m.type().name(),
                m.publicKey(),
                m.allowedIps(),
                m.endpointIp(),
                m.endpointPort(),
                m.latestHandshake(),
                m.transferRx(),
                m.transferTx(),
                m.lanCidr(),
                m.lanAddress(),
                m.runsDocker(),
                m.dockerPort(),
                m.deviceCategory().name(),
                m.effectiveSshAccess(),
                hasCredential,
                vaierServer,
                sshServerPresence,
                effectiveUser == null ? null : effectiveUser.username(),
                effectiveUser != null && effectiveUser.privileged(),
                // #333: whether this machine could carry a whole network into the fleet. The browser used
                // to decide this itself, from a type set that included LAN_SERVER — a machine with no
                // tunnel to route into. The domain owns the rule; the answer travels as a boolean.
                m.canRelayALan(),
                m.acceptsSetupScript()
            );
        }
    }
}
