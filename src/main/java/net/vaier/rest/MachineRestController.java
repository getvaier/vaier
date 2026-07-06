package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.Machine;
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
    private final SetMachineSshAccessUseCase setMachineSshAccessUseCase;

    @GetMapping
    public List<MachineResponse> list() {
        return getMachinesUseCase.getAllMachines().stream()
            .map(MachineResponse::from)
            .toList();
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
