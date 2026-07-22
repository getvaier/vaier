package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.BackupFleet;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineNudge;
import net.vaier.domain.MachineNudges;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Reachability;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/machines")
@RequiredArgsConstructor
public class MachineRestController {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVaierServerUseCase getVaierServerUseCase;
    private final SetMachineSshAccessUseCase setMachineSshAccessUseCase;
    private final GetHostCredentialUseCase getHostCredentialUseCase;
    private final ClearHostKeyUseCase clearHostKeyUseCase;
    private final GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;
    private final SetDiskWatchUseCase setDiskWatchUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupServersUseCase getBackupServersUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;

    @GetMapping
    public List<MachineResponse> list() {
        return getMachinesUseCase.getAllMachines().stream()
            .map(MachineResponse::from)
            .toList();
    }

    /**
     * The Vaier server host as a machine (#311): its canonical name, effective SSH access, and
     * whether a host credential is stored for it. Feeds the dedicated Vaier-server card's SSH-access
     * toggle and credential control. Writes reuse {@code /machines/{name}/ssh-access} and
     * {@code /machines/{name}/ssh-credential} with the returned {@code name}.
     */
    @GetMapping("/vaier-server")
    public VaierServerResponse vaierServer() {
        Machine server = getVaierServerUseCase.getVaierServerMachine();
        boolean hasCredential = getHostCredentialUseCase.getHostCredential(server.name())
            .map(v -> v.hasSecret()).orElse(false);
        return new VaierServerResponse(server.name(), server.effectiveSshAccess(), hasCredential);
    }

    record VaierServerResponse(String name, boolean sshAccess, boolean hasCredential) {}

    /**
     * The progressive-adoption nudges Vaier suggests for one machine: evidence-backed, single yes/no
     * prompts to adopt one more capability — publish its exposed services, back it up, or make it the
     * fleet's backup server. Each carries its own "why" from already-cached state.
     *
     * <p><b>Composed at the driving edge.</b> The controller gathers each signal from an existing
     * {@code *UseCase} — the machine, its publishable services, whether a credential is stored, whether
     * anything is backed up, the backup fleet, reachability — and hands them to the pure-domain
     * {@link MachineNudges} assembler, which owns the decisions. No application service reaches across
     * domains to collect nudges, and none implements a driven port to expose them. 404 when no machine
     * bears that name. A non-whitelisted path under {@code /machines}, so it is admin-gated automatically.
     */
    @GetMapping("/{machine}/nudges")
    public List<NudgeResponse> nudges(@PathVariable String machine) {
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        Machine target = machines.stream()
            .filter(m -> m.name().equals(machine))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machine));

        String vaierServerName = getVaierServerUseCase.getVaierServerMachine().name();
        Map<String, String> lanServerNameByAddress = machines.stream()
            .filter(m -> m.lanAddress() != null)
            .collect(Collectors.toMap(Machine::lanAddress, Machine::name, (a, b) -> a));

        int publishableCount = (int) getPublishableServicesUseCase.getPublishableServices().stream()
            .filter(s -> s.ownerMachineName(vaierServerName, lanServerNameByAddress)
                .map(machine::equals).orElse(false))
            .count();
        boolean hasCredential = getHostCredentialUseCase.getHostCredential(machine)
            .map(v -> v.hasSecret()).orElse(false);
        boolean alreadyProtected = getBackupJobsUseCase.getBackupJobs().stream()
            .anyMatch(j -> machine.equals(j.machineName()));
        BackupFleet fleet = new BackupFleet(getBackupServersUseCase.getBackupServers());
        Map<String, Reachability> lanReachability = target.lanAddress() == null ? Map.of()
            : Map.of(target.lanAddress(), getLanServerReachabilityUseCase.getReachability(target.lanAddress()));
        boolean reachable = target.isReachable(lanReachability);

        return MachineNudges.forMachine(target, publishableCount, reachable, hasCredential,
                alreadyProtected, fleet).stream()
            .map(NudgeResponse::from)
            .toList();
    }

    /** One nudge flattened for the browser: its kind, operator-facing title, evidence, and action hint. */
    public record NudgeResponse(String kind, String title, String evidence, String action) {
        static NudgeResponse from(MachineNudge n) {
            return new NudgeResponse(n.kind().name(), n.title(), n.evidence(), n.action());
        }
    }

    /**
     * Sets whether Vaier offers SSH for a machine — the credential control now, the web terminal
     * later. Writes an explicit override and returns the resulting effective state. 404 (via
     * {@code NotFoundException}) when no machine bears that name. Admin-gated (non-whitelisted path).
     */
    @PatchMapping("/{machine}/ssh-access")
    public SshAccessResponse setSshAccess(@PathVariable String machine,
                                          @RequestBody SshAccessRequest request) {
        boolean enabled = request != null && request.enabled();
        boolean effective = setMachineSshAccessUseCase.setMachineSshAccess(machine, enabled);
        return new SshAccessResponse(effective);
    }

    /**
     * A machine's filesystems, read now (#323 slice C, fixed by #325). {@code RemoteDiskWatcher} has computed
     * this on a schedule since the disk alerts shipped, but only ever emailed about it — and until #325 it
     * read {@code df -P /}, so it saw the root filesystem and only the root filesystem. On the NAS that is
     * the 2.3 GB DSM system partition (88% by design) while {@code /volume1} — 11.6 TB, every borg backup —
     * was invisible. So this returns <b>every</b> real filesystem.
     *
     * <p>A sibling of {@code /machines/{machine}/files}: a non-whitelisted path under {@code /machines}, so
     * it sits behind the admin auth chain automatically. Reading a machine's disks is never anonymous.
     *
     * <p>A disk that cannot be read is a {@code DiskUnreadableException} → {@code 502}, carrying the reason
     * verbatim. It is never a {@code 0%} and never an empty list.
     */
    @GetMapping("/{machine}/disk")
    public List<FilesystemResponse> disk(@PathVariable String machine) {
        return getMachineDiskUsageUseCase.getDiskUsage(machine).stream()
            .map(fs -> new FilesystemResponse(fs.machineName(), fs.device(), fs.mountPoint(),
                fs.sizeKb(), fs.usedKb(), fs.availableKb(), fs.size(), fs.available(),
                fs.usedPercent(), fs.thresholdPercent(), fs.watched(), fs.aboveThreshold()))
            .toList();
    }

    /**
     * Watch or mute one filesystem on one machine, optionally at its own threshold (#325).
     *
     * <p>The mount point travels in the <b>body</b>, not the path: a mount point contains slashes
     * ({@code /volume1}, and worse), and a path variable carrying them is a routing problem and an encoding
     * bug waiting to happen. In a body a slash is just a character.
     */
    @PutMapping("/{machine}/disk/watch")
    public DiskWatchResponse setDiskWatch(@PathVariable String machine,
                                          @RequestBody DiskWatchRequest request) {
        setDiskWatchUseCase.setDiskWatch(machine, request.mountPoint(), request.watched(),
            request.thresholdPercent());
        return new DiskWatchResponse(machine, request.mountPoint(), request.watched(),
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
    record DiskWatchResponse(String machine, String mountPoint, boolean watched,
                             Integer thresholdPercent) {}

    /**
     * Forget the pinned SSH host key for a machine (#308), so the next terminal connect re-pins on
     * first use. Use after a host is legitimately rebuilt and a host-key mismatch is refusing connects.
     */
    @DeleteMapping("/{machine}/host-key")
    public ResponseEntity<Void> clearHostKey(@PathVariable String machine) {
        clearHostKeyUseCase.clearHostKey(machine);
        return ResponseEntity.noContent().build();
    }

    record SshAccessRequest(boolean enabled) {}

    record SshAccessResponse(boolean sshAccess) {}

    public record MachineResponse(
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
        boolean sshAccess
    ) {
        static MachineResponse from(Machine m) {
            return new MachineResponse(
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
                m.effectiveSshAccess()
            );
        }
    }
}
