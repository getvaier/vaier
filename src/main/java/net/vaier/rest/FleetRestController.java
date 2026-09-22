package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.domain.BackupFleet;
import net.vaier.domain.FleetNudge;
import net.vaier.domain.FleetNudges;
import net.vaier.domain.FleetSignals;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The fleet root's own reads (#336). Like a machine's nudges, the fleet's are <b>composed at the driving
 * edge</b>: this controller gathers each signal from an existing {@code *UseCase} and hands them to the
 * pure-domain {@link FleetNudges} assembler, which owns the ordering, the cap and every "should we say this?".
 * No application service reaches across domains to collect them.
 */
@RestController
@RequestMapping("/fleet")
@RequiredArgsConstructor
public class FleetRestController {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final GetBackupServersUseCase getBackupServersUseCase;
    private final GetBackupRepositoriesUseCase getBackupRepositoriesUseCase;
    private final ListAccessEntriesUseCase listAccessEntriesUseCase;
    private final GetAppSettingsUseCase getAppSettingsUseCase;

    @GetMapping("/nudges")
    public List<FleetNudgeResponse> nudges() {
        AppSettingsResult settings = getAppSettingsUseCase.getSettings();
        FleetSignals signals = new FleetSignals(
            getMachinesUseCase.getAllMachines(),
            getPublishableServicesUseCase.getPublishableServices(),
            getPublishedServicesUseCase.getPublishedServices().size(),
            new BackupFleet(getBackupServersUseCase.getBackupServers()),
            getBackupRepositoriesUseCase.getBackupRepositories().size(),
            settings.survivalKitWritten(),
            listAccessEntriesUseCase.listAccessEntries(),
            settings.smtpConfigured());
        return FleetNudges.forFleet(signals).stream().map(FleetNudgeResponse::from).toList();
    }

    /** One rung flattened for the browser; {@code value} is the machine it points at, where it points at one. */
    public record FleetNudgeResponse(String kind, String title, String evidence, String action, String value) {
        static FleetNudgeResponse from(FleetNudge n) {
            return new FleetNudgeResponse(n.kind().name(), n.title(), n.evidence(), n.action(), n.value());
        }
    }
}
