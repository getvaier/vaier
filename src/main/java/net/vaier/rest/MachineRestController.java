package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.Machine;
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
