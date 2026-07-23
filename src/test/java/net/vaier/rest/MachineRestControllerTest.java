package net.vaier.rest;

import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase.MachineFilesystemUco;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineNudge;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.PublishableService;
import net.vaier.domain.PublishableService.PublishableSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MachineRestControllerTest {

    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock GetVaierServerUseCase getVaierServerUseCase;
    @Mock SetMachineSshAccessUseCase setMachineSshAccessUseCase;
    @Mock GetHostCredentialUseCase getHostCredentialUseCase;
    @Mock ClearHostKeyUseCase clearHostKeyUseCase;
    @Mock GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;
    @Mock SetDiskWatchUseCase setDiskWatchUseCase;
    @Mock GetPublishableServicesUseCase getPublishableServicesUseCase;
    @Mock GetBackupJobsUseCase getBackupJobsUseCase;
    @Mock GetBackupServersUseCase getBackupServersUseCase;
    @Mock GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;

    @InjectMocks MachineRestController controller;

    @BeforeEach
    void defaultNoStoredCredential() {
        // list() reads a credential per machine; default every lookup to "none stored" so the tests
        // that don't care about credentials keep asserting only what they set. Tests that do care
        // override the specific name.
        lenient().when(getHostCredentialUseCase.getHostCredential(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void list_emptyWhenNothingRegistered() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
    }

    @Test
    void list_returnsMachinesAcrossWgPeerAndLanServer() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine("alice", MachineType.UBUNTU_SERVER,
                "pubkey", "10.13.13.2/32", "1.2.3.4", "51820",
                "1700000000", "100", "200",
                null, null, true, null, net.vaier.domain.DeviceCategory.SERVER, null),
            new Machine("nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, net.vaier.domain.DeviceCategory.NAS, null)
        ));

        var response = controller.list();

        assertThat(response).extracting("name", "type")
            .containsExactly(
                tuple("alice", "UBUNTU_SERVER"),
                tuple("nas", "LAN_SERVER"));
        assertThat(response.get(0).publicKey()).isEqualTo("pubkey");
        assertThat(response.get(1).publicKey()).isNull();
        assertThat(response.get(1).runsDocker()).isTrue();
        assertThat(response.get(1).dockerPort()).isEqualTo(2375);
        assertThat(response.get(0).deviceCategory()).isEqualTo("SERVER");
        assertThat(response.get(1).deviceCategory()).isEqualTo("NAS");
    }

    @Test
    void list_reportsHasCredentialPerMachine() {
        // The Explorer tree gates a machine's Files/Disk entries on Vaier actually holding an SSH
        // credential for it, not merely the ssh-access toggle — so GET /machines carries hasCredential
        // per machine: true iff a stored host credential with a secret exists for that name.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine("nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, DeviceCategory.NAS, null),
            new Machine("printer", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.20", false, null, DeviceCategory.PRINTER, null)
        ));
        when(getHostCredentialUseCase.getHostCredential("nas")).thenReturn(
            Optional.of(new HostCredentialView("nas", "root", AuthMethod.PASSWORD, true)));

        var response = controller.list();

        assertThat(response).extracting("name", "hasCredential")
            .containsExactly(tuple("nas", true), tuple("printer", false));
    }

    @Test
    void list_exposesEffectiveSshAccess() {
        // A server defaults SSH-on; a phone client defaults SSH-off — both with no override.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine("alice", MachineType.UBUNTU_SERVER,
                null, null, null, null, null, null, null,
                null, null, true, null, net.vaier.domain.DeviceCategory.SERVER, null),
            new Machine("phone", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, null, null,
                null, null, false, null, net.vaier.domain.DeviceCategory.PHONE, null)
        ));

        var response = controller.list();

        assertThat(response.get(0).sshAccess()).isTrue();
        assertThat(response.get(1).sshAccess()).isFalse();
    }

    // --- SSH access override (#307) ---

    @Test
    void setSshAccess_delegatesAndReturnsEffectiveState() {
        when(setMachineSshAccessUseCase.setMachineSshAccess("nas", false)).thenReturn(false);

        var response = controller.setSshAccess("nas", new MachineRestController.SshAccessRequest(false));

        assertThat(response.sshAccess()).isFalse();
        verify(setMachineSshAccessUseCase).setMachineSshAccess("nas", false);
    }

    @Test
    void setSshAccess_enabledTrue_delegates() {
        when(setMachineSshAccessUseCase.setMachineSshAccess("nas", true)).thenReturn(true);

        var response = controller.setSshAccess("nas", new MachineRestController.SshAccessRequest(true));

        assertThat(response.sshAccess()).isTrue();
    }

    // --- Vaier server singleton (#311) ---

    @Test
    void vaierServer_reportsEffectiveSshAccessAndCredentialPresence() {
        when(getVaierServerUseCase.getVaierServerMachine()).thenReturn(Machine.vaierServer(null));
        when(getHostCredentialUseCase.getHostCredential(LanAnchor.VAIER_SERVER_NAME))
            .thenReturn(Optional.of(new HostCredentialView(LanAnchor.VAIER_SERVER_NAME, "root",
                AuthMethod.PASSWORD, true)));

        var response = controller.vaierServer();

        assertThat(response.name()).isEqualTo(LanAnchor.VAIER_SERVER_NAME);
        assertThat(response.sshAccess()).isTrue();  // server defaults on
        assertThat(response.hasCredential()).isTrue();
    }

    @Test
    void vaierServer_noCredentialStored_reportsHasCredentialFalse() {
        when(getVaierServerUseCase.getVaierServerMachine()).thenReturn(Machine.vaierServer(false));
        when(getHostCredentialUseCase.getHostCredential(LanAnchor.VAIER_SERVER_NAME))
            .thenReturn(Optional.empty());

        var response = controller.vaierServer();

        assertThat(response.sshAccess()).isFalse();
        assertThat(response.hasCredential()).isFalse();
    }

    // --- clear host key (#308) ---

    @Test
    void clearHostKey_returns204AndDelegates() {
        var response = controller.clearHostKey("nas");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(clearHostKeyUseCase).clearHostKey("nas");
    }

    // --- progressive-adoption nudges (edge-composed) ---
    //
    // The controller (a driving adapter) gathers each signal from an existing *UseCase and hands them to
    // the pure-domain MachineNudges assembler, which owns the decisions. No service composes across
    // domains and no service implements a driven port to expose nudges.

    @Test
    void nudges_composesFromTheGatheredSignals() {
        // A reachable, storage-class peer, with an exposed service, an SSH credential, nothing backed up,
        // and no backup server anywhere ⇒ all three nudges fire.
        String freshHandshake = String.valueOf(System.currentTimeMillis() / 1000);
        Machine alice = new Machine("alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            "1.2.3.4", "51820", freshHandshake, "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(alice));
        when(getVaierServerUseCase.getVaierServerMachine()).thenReturn(Machine.vaierServer(null));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of(
            new PublishableService(PublishableSource.PEER, "alice", "10.13.13.2", "grafana", 3000, null, false)));
        when(getHostCredentialUseCase.getHostCredential("alice")).thenReturn(
            Optional.of(new HostCredentialView("alice", "root", AuthMethod.PASSWORD, true)));
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of());

        var response = controller.nudges("alice");

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .containsExactly(MachineNudge.Kind.PUBLISH.name(), MachineNudge.Kind.BACK_UP.name(),
                MachineNudge.Kind.DESIGNATE_BACKUP_SERVER.name());
        assertThat(response.get(0).title()).isNotBlank();
    }

    @Test
    void nudges_unknownMachine_404() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of());

        assertThatThrownBy(() -> controller.nudges("ghost"))
            .isInstanceOf(NotFoundException.class);
    }

    // --- a machine's filesystems (#323 slice C, fixed by #325) ---
    //
    // A sibling of /machines/{machine}/files: a non-whitelisted path under /machines, so it sits behind the
    // admin auth chain automatically — reading or configuring a machine's disks is never anonymous. (The
    // whitelist is an explicit Path() list on the vaier-public Traefik router in docker-compose.yml; nothing
    // under /machines is on it.)

    private static MachineFilesystemUco filesystem(String machine, String mount, int usedPercent,
                                                   int threshold, boolean watched, boolean above) {
        return new MachineFilesystemUco(machine, "/dev/sda1", mount, 1000L, 500L, 500L, "1.0 MiB",
            "500.0 KiB", usedPercent, threshold, watched, above);
    }

    @Test
    void disk_reportsEveryFilesystem_notJustRoot() {
        // The #325 fix at the REST seam: /volume1 (39%, the volume that holds every borg backup) has to come
        // back alongside / (88%, the DSM system partition), or the operator still cannot see the disk that
        // matters.
        when(getMachineDiskUsageUseCase.getDiskUsage("NAS")).thenReturn(List.of(
            filesystem("NAS", "/", 88, 95, true, false),
            filesystem("NAS", "/volume1", 39, 85, true, false)));

        var response = controller.disk("NAS");

        assertThat(response).extracting(MachineRestController.FilesystemResponse::mountPoint)
            .containsExactly("/", "/volume1");
    }

    @Test
    void disk_reportsTheUsageTheSizeAndTheThresholdEachFilesystemIsJudgedAgainst() {
        when(getMachineDiskUsageUseCase.getDiskUsage("Apalveien 5")).thenReturn(List.of(
            new MachineFilesystemUco("Apalveien 5", "/dev/root", "/", 30298176L, 18178905L, 10566487L,
                "28.9 GiB", "10.1 GiB", 63, 80, true, false)));

        var root = controller.disk("Apalveien 5").get(0);

        assertThat(root.machine()).isEqualTo("Apalveien 5");
        assertThat(root.mountPoint()).isEqualTo("/");
        assertThat(root.usedPercent()).isEqualTo(63);
        assertThat(root.size()).isEqualTo("28.9 GiB");
        assertThat(root.available()).isEqualTo("10.1 GiB");
        assertThat(root.thresholdPercent()).isEqualTo(80);
        assertThat(root.watched()).isTrue();
        assertThat(root.aboveThreshold()).isFalse();
    }

    @Test
    void disk_carriesTheDomainsOwnPressureVerdict_theBrowserNeverRecomputesIt() {
        when(getMachineDiskUsageUseCase.getDiskUsage("Colina 27")).thenReturn(List.of(
            filesystem("Colina 27", "/", 91, 80, true, true)));

        assertThat(controller.disk("Colina 27").get(0).aboveThreshold()).isTrue();
    }

    // --- setting one filesystem's watch (#325) ---

    @Test
    void setDiskWatch_takesTheMountPointInTheBody_becauseAMountPointContainsSlashes() {
        // /volume1/@docker/... in a path variable would be a routing nightmare and an encoding bug waiting to
        // happen. The mount point travels in the body, where a slash is just a character.
        var response = controller.setDiskWatch("NAS",
            new MachineRestController.DiskWatchRequest("/volume1", true, 90));

        verify(setDiskWatchUseCase).setDiskWatch("NAS", "/volume1", true, 90);
        assertThat(response.mountPoint()).isEqualTo("/volume1");
        assertThat(response.watched()).isTrue();
        assertThat(response.thresholdPercent()).isEqualTo(90);
    }

    @Test
    void setDiskWatch_canMuteAFilesystem_andCanClearItsOwnThreshold() {
        controller.setDiskWatch("NAS", new MachineRestController.DiskWatchRequest("/", false, null));

        verify(setDiskWatchUseCase).setDiskWatch("NAS", "/", false, null);
    }
}
