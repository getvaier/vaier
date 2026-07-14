package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.Machine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * A machine's disk, read now (#323 slice C) — the one endpoint the Explorer tree needed that Vaier did
     * not already have. {@code RemoteDiskWatcher} has computed this on a schedule since the disk alerts
     * shipped, but only ever emailed about it; this lets the operator look at it.
     *
     * <p>A sibling of {@code /machines/{machine}/files}: a non-whitelisted path under {@code /machines}, so
     * it sits behind the admin auth chain automatically. Reading a machine's disk is never anonymous.
     *
     * <p>A disk that cannot be read is a {@code DiskUnreadableException} → {@code 502}, carrying the reason
     * verbatim. It is never a {@code 0%}.
     */
    @GetMapping("/{machine}/disk")
    public DiskResponse disk(@PathVariable String machine) {
        var usage = getMachineDiskUsageUseCase.getDiskUsage(machine);
        return new DiskResponse(usage.machineName(), usage.usedPercent(), usage.thresholdPercent(),
            usage.aboveThreshold());
    }

    /**
     * A disk reading, with the threshold it is judged against and the domain's verdict on it. The verdict
     * travels rather than the browser recomputing it, so "under pressure" means one thing in the alert
     * email and in the Explorer.
     */
    record DiskResponse(String machine, int usedPercent, int thresholdPercent, boolean aboveThreshold) {}

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
