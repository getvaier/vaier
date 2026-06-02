package net.vaier.rest;

import net.vaier.application.DeleteLanServerUseCase;
import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.application.GenerateLanServerSetupScriptUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.domain.Reachability;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.domain.port.ForGettingLanServers.LanServerView;
import net.vaier.application.RegisterLanServerUseCase;
import net.vaier.application.RenameLanServerUseCase;
import net.vaier.application.ResolveLanAnchorUseCase;
import net.vaier.application.UpdateLanServerDescriptionUseCase;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerRestControllerTest {

    @Mock RegisterLanServerUseCase registerLanServerUseCase;
    @Mock RenameLanServerUseCase renameLanServerUseCase;
    @Mock UpdateLanServerDescriptionUseCase updateLanServerDescriptionUseCase;
    @Mock DeleteLanServerUseCase deleteLanServerUseCase;
    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock GetLanServerReachabilityUseCase reachabilityUseCase;
    @Mock GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    @Mock ResolveLanAnchorUseCase resolveLanAnchorUseCase;
    @Mock GenerateLanServerSetupScriptUseCase generateLanServerSetupScriptUseCase;

    @InjectMocks
    LanServerRestController controller;

    // --- lan-anchor ---

    @Test
    void lanAnchor_routable_returnsRoutedViaAndCidr() {
        LanAnchor anchor = LanAnchor.resolve("172.31.5.20", List.of(), "172.31.0.0/16").orElseThrow();
        when(resolveLanAnchorUseCase.resolveLanAnchor("172.31.5.20")).thenReturn(Optional.of(anchor));

        var resp = controller.lanAnchor("172.31.5.20");

        assertThat(resp.routable()).isTrue();
        assertThat(resp.routedVia()).isEqualTo("Vaier server");
        assertThat(resp.cidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void lanAnchor_notRoutable_returnsRoutableFalse() {
        when(resolveLanAnchorUseCase.resolveLanAnchor("10.99.99.99")).thenReturn(Optional.empty());

        var resp = controller.lanAnchor("10.99.99.99");

        assertThat(resp.routable()).isFalse();
        assertThat(resp.routedVia()).isNull();
        assertThat(resp.cidr()).isNull();
    }

    @Test
    void register_runsDockerTrueWithDockerPort_delegatesToUseCase() {
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, 2375, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375, null);
    }

    @Test
    void register_runsDockerFalseWithoutDockerPort_delegatesToUseCase() {
        var request = new LanServerRestController.RegisterRequest("printer", "192.168.3.20", false, null, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("printer", "192.168.3.20", false, null, null);
    }

    @Test
    void register_runsDockerTrueWithoutDockerPort_returns400() {
        doThrow(new IllegalArgumentException("dockerPort is required"))
            .when(registerLanServerUseCase).register("nas", "192.168.3.50", true, null, null);
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, null, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_useCaseThrowsIllegalArgument_returns400() {
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(registerLanServerUseCase).register("nas", "10.99.99.99", true, 2375, null);
        var request = new LanServerRestController.RegisterRequest("nas", "10.99.99.99", true, 2375, null);

        ResponseEntity<Void> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void delete_callsUseCase() {
        ResponseEntity<Void> response = controller.delete("nas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deleteLanServerUseCase).delete("nas");
    }

    // --- {name}/setup.sh (#249) ---

    @Test
    void downloadSetupScript_returns200WithShellAttachment() {
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenReturn(Optional.of("#!/usr/bin/env bash\nip route replace 172.31.16.0/20 via 192.168.3.121\n"));

        ResponseEntity<?> response = controller.downloadSetupScript("nuc02");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/x-sh"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("filename=nuc02-setup.sh");
        assertThat(response.getBody()).asString().contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
    }

    @Test
    void downloadSetupScript_returns404WhenNothingToSetUp() {
        when(generateLanServerSetupScriptUseCase.generateSetupScript("ghost")).thenReturn(Optional.empty());

        assertThat(controller.downloadSetupScript("ghost").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadSetupScript_returns409WhenRelayHasNoLanAddress() {
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenThrow(new IllegalStateException("Relay peer apalveien5 has no LAN address set"));

        assertThat(controller.downloadSetupScript("nuc02").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void list_returnsRegisteredServersWithRelayNameAndReachability() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5"),
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.50")).thenReturn(Reachability.UNKNOWN);
        when(reachabilityUseCase.getReachability("192.168.3.20")).thenReturn(Reachability.OK);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of());

        var response = controller.list();

        assertThat(response).hasSize(2);
        assertThat(response.get(0).name()).isEqualTo("nas");
        assertThat(response.get(0).lanAddress()).isEqualTo("192.168.3.50");
        assertThat(response.get(0).runsDocker()).isTrue();
        assertThat(response.get(0).dockerPort()).isEqualTo(2375);
        assertThat(response.get(0).relayPeerName()).isEqualTo("apalveien5");
        assertThat(response.get(0).reachability()).isEqualTo("UNKNOWN");
        assertThat(response.get(1).name()).isEqualTo("printer");
        assertThat(response.get(1).runsDocker()).isFalse();
        assertThat(response.get(1).dockerPort()).isNull();
        assertThat(response.get(1).reachability()).isEqualTo("OK");
    }

    @Test
    void list_dockerHostWithFailingScrape_statusIsDegraded() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.50")).thenReturn(Reachability.OK);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of(
            new LanServerContainers("nas", "192.168.3.50", 2375, "apalveien5", "UNREACHABLE", List.of())));

        var response = controller.list();

        assertThat(response.get(0).status()).isEqualTo(net.vaier.domain.MachineStatus.DEGRADED);
    }

    @Test
    void list_dockerHostWithOkScrape_statusIsOk() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.50")).thenReturn(Reachability.OK);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of(
            new LanServerContainers("nas", "192.168.3.50", 2375, "apalveien5", "OK", List.of())));

        var response = controller.list();

        assertThat(response.get(0).status()).isEqualTo(net.vaier.domain.MachineStatus.OK);
    }

    @Test
    void list_unknownReachability_statusIsUnknown() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.20")).thenReturn(Reachability.UNKNOWN);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of());

        assertThat(controller.list().get(0).status()).isEqualTo(net.vaier.domain.MachineStatus.UNKNOWN);
    }

    @Test
    void list_unreachableHost_statusIsDown() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.50")).thenReturn(Reachability.DOWN);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of());

        assertThat(controller.list().get(0).status()).isEqualTo(net.vaier.domain.MachineStatus.DOWN);
    }

    // --- rename (#55) ---

    @Test
    void rename_returns204OnSuccess() {
        var response = controller.rename("nas", new LanServerRestController.RenameRequest("media-nas"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(renameLanServerUseCase).rename("nas", "media-nas");
    }

    @Test
    void rename_returns404WhenLanServerNotFound() {
        doThrow(new java.util.NoSuchElementException("LAN server not found: ghost"))
            .when(renameLanServerUseCase).rename("ghost", "phantom");

        var response = controller.rename("ghost", new LanServerRestController.RenameRequest("phantom"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void rename_returns409WhenNewNameAlreadyTaken() {
        doThrow(new IllegalStateException("A LAN server named printer already exists"))
            .when(renameLanServerUseCase).rename("nas", "printer");

        var response = controller.rename("nas", new LanServerRestController.RenameRequest("printer"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void rename_returns400WhenNewNameBlank() {
        doThrow(new IllegalArgumentException("New LAN server name must not be blank"))
            .when(renameLanServerUseCase).rename("nas", "  ");

        var response = controller.rename("nas", new LanServerRestController.RenameRequest("  "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // --- description (#54) ---

    @Test
    void register_passesDescriptionToUseCase() {
        var request = new LanServerRestController.RegisterRequest(
                "nas", "192.168.3.50", true, 2375, "Synology NAS");

        controller.register(request);

        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375, "Synology NAS");
    }

    @Test
    void updateDescription_returns204OnSuccess() {
        var response = controller.updateDescription(
                "nas", new LanServerRestController.UpdateDescriptionRequest("Synology in the closet"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanServerDescriptionUseCase).updateDescription("nas", "Synology in the closet");
    }

    @Test
    void updateDescription_returns404WhenLanServerNotFound() {
        doThrow(new java.util.NoSuchElementException("LAN server not found: ghost"))
            .when(updateLanServerDescriptionUseCase).updateDescription("ghost", "anything");

        var response = controller.updateDescription(
                "ghost", new LanServerRestController.UpdateDescriptionRequest("anything"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void list_includesLastSeenEpochSecFromReachabilityUseCase() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "apalveien5"),
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability("192.168.3.50")).thenReturn(Reachability.OK);
        when(reachabilityUseCase.getReachability("192.168.3.20")).thenReturn(Reachability.UNKNOWN);
        when(reachabilityUseCase.getLastSeenEpochSec("192.168.3.50")).thenReturn(1714000000L);
        when(reachabilityUseCase.getLastSeenEpochSec("192.168.3.20")).thenReturn(null);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of());

        var response = controller.list();

        assertThat(response.get(0).lastSeen()).isEqualTo(1714000000L);
        assertThat(response.get(1).lastSeen()).isNull();
    }
}
