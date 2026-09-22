package net.vaier.rest;

import net.vaier.application.GetAppSettingsUseCase;
import net.vaier.application.GetAppSettingsUseCase.AppSettingsResult;
import net.vaier.application.GetBackupRepositoriesUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.FleetNudge;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;
import net.vaier.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The fleet-level nudge ladder (#336), composed at the driving edge from what the use cases already hold. */
class FleetRestControllerTest {

    private final GetMachinesUseCase getMachines = mock(GetMachinesUseCase.class);
    private final GetPublishableServicesUseCase getPublishable = mock(GetPublishableServicesUseCase.class);
    private final GetPublishedServicesUseCase getPublished = mock(GetPublishedServicesUseCase.class);
    private final GetBackupServersUseCase getBackupServers = mock(GetBackupServersUseCase.class);
    private final GetBackupRepositoriesUseCase getRepositories = mock(GetBackupRepositoriesUseCase.class);
    private final ListAccessEntriesUseCase listAccessEntries = mock(ListAccessEntriesUseCase.class);
    private final GetAppSettingsUseCase getAppSettings = mock(GetAppSettingsUseCase.class);
    private final FleetRestController controller = new FleetRestController(getMachines, getPublishable,
        getPublished, getBackupServers, getRepositories, listAccessEntries, getAppSettings);

    @Test
    void nudges_composesTheLadderFromTheGatheredSignals() {
        Machine alice = new Machine(MachineId.generate(), "alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            null, null, null, null, null, null, null, true, null, DeviceCategory.SERVER, null);
        when(getMachines.getAllMachines()).thenReturn(List.of(alice));
        when(getPublishable.getPublishableServices()).thenReturn(List.of(new PublishableService(
            PublishableSource.PEER, alice.id().value(), "alice", "10.13.13.2", "grafana", 3000, null, false)));
        when(getPublished.getPublishedServices()).thenReturn(List.of());
        when(getBackupServers.getBackupServers()).thenReturn(List.of());
        when(getRepositories.getBackupRepositories()).thenReturn(List.of());
        when(listAccessEntries.listAccessEntries()).thenReturn(List.of(
            AccessEntry.builder().email("new@example.com").role(Role.PENDING).groups(List.of()).build()));
        when(getAppSettings.getSettings()).thenReturn(new AppSettingsResult("example.com", null, null, null, null,
            null, null, null, null, null, 80, false, 3, "Europe/Oslo", false, false, false, false));

        List<FleetRestController.FleetNudgeResponse> body = controller.nudges();

        assertThat(body).extracting(FleetRestController.FleetNudgeResponse::kind).containsExactly(
            FleetNudge.Kind.LET_PEOPLE_IN.name(), FleetNudge.Kind.PUBLISH.name(),
            FleetNudge.Kind.DESIGNATE_BACKUP_SERVER.name());
        assertThat(body.get(1).value()).isEqualTo(alice.id().value());
        assertThat(body.get(0).title()).isEqualTo("1 person is waiting to be let in");
    }
}
