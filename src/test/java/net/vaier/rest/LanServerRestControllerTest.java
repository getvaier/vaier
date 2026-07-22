package net.vaier.rest;

import net.vaier.domain.NotFoundException;
import net.vaier.domain.ConflictException;
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
import net.vaier.domain.SetupToken;
import net.vaier.domain.port.ForVendingSetupTokens;
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

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LanServerRestControllerTest {

    @Mock RegisterLanServerUseCase registerLanServerUseCase;
    @Mock RenameLanServerUseCase renameLanServerUseCase;
    @Mock UpdateLanServerDescriptionUseCase updateLanServerDescriptionUseCase;
    @Mock net.vaier.application.UpdateLanServerDeviceCategoryUseCase updateLanServerDeviceCategoryUseCase;
    @Mock DeleteLanServerUseCase deleteLanServerUseCase;
    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock GetLanServerReachabilityUseCase reachabilityUseCase;
    @Mock GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    @Mock ResolveLanAnchorUseCase resolveLanAnchorUseCase;
    @Mock GenerateLanServerSetupScriptUseCase generateLanServerSetupScriptUseCase;
    @Mock ForVendingSetupTokens forVendingSetupTokens;
    @Mock net.vaier.application.ProbeLanHostUseCase probeLanHostUseCase;

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
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, 2375, null, null);

        var response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375, null, null);
    }

    @Test
    void register_runsDockerFalseWithoutDockerPort_delegatesToUseCase() {
        var request = new LanServerRestController.RegisterRequest("printer", "192.168.3.20", false, null, null, null);

        var response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(registerLanServerUseCase).register("printer", "192.168.3.20", false, null, null, null);
    }

    // --- probe (manual add-by-address parity — Community-available, not Enterprise) ---

    @Test
    void probe_reachable_reportsPortsFlagsCategoryAndRoute() {
        var host = new net.vaier.domain.DiscoveredLanMachine(
            "192.168.3.50", "synology-nas", List.of(22, 2375), "apalveien5");
        when(probeLanHostUseCase.probeHost("192.168.3.50"))
            .thenReturn(net.vaier.domain.LanHostProbe.reached(host, "Apalveien 5"));

        var resp = controller.probe(new LanServerRestController.ProbeRequest("192.168.3.50"));

        assertThat(resp.reachable()).isTrue();
        assertThat(resp.openPorts()).containsExactly(22, 2375);
        assertThat(resp.sshAvailable()).isTrue();
        assertThat(resp.runsDocker()).isTrue();
        assertThat(resp.dockerPort()).isEqualTo(2375);
        assertThat(resp.guessedCategory()).isEqualTo("NAS");
        assertThat(resp.routedVia()).isEqualTo("Apalveien 5");
    }

    @Test
    void probe_notReachable_returnsReachableFalseWithNoFieldsAndNo500() {
        when(probeLanHostUseCase.probeHost("10.99.99.99"))
            .thenReturn(net.vaier.domain.LanHostProbe.notReachable());

        var resp = controller.probe(new LanServerRestController.ProbeRequest("10.99.99.99"));

        assertThat(resp.reachable()).isFalse();
        assertThat(resp.openPorts()).isEmpty();
        assertThat(resp.sshAvailable()).isFalse();
        assertThat(resp.runsDocker()).isFalse();
        assertThat(resp.dockerPort()).isNull();
        assertThat(resp.guessedCategory()).isNull();
        assertThat(resp.routedVia()).isNull();
    }

    // --- register with an optional SSH credential (manual add-by-address parity with adopt) ---

    @Test
    void register_withVerifiedCredential_reportsCredentialStored() {
        var created = new LanServer("roon", "192.168.3.50", true, 2375, null, net.vaier.domain.DeviceCategory.NAS);
        var verification = new net.vaier.domain.SshCredentialVerification(true, true, "SHA256:abc");
        when(registerLanServerUseCase.register(eq("roon"), eq("192.168.3.50"), eq(true), eq(2375),
                org.mockito.ArgumentMatchers.isNull(), eq(net.vaier.domain.DeviceCategory.NAS),
                org.mockito.ArgumentMatchers.any(net.vaier.domain.SshCredentialDraft.class)))
            .thenReturn(new RegisterLanServerUseCase.RegistrationOutcome(created, verification, true));
        var request = new LanServerRestController.RegisterRequest("roon", "192.168.3.50", true, 2375, null, "NAS",
            new LanServerRestController.CredentialBlock("root", "PASSWORD", "pw", null));

        var response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (LanServerRestController.RegisterResponse) response.getBody();
        assertThat(body.name()).isEqualTo("roon");
        assertThat(body.credentialProvided()).isTrue();
        assertThat(body.credentialVerified()).isTrue();
        assertThat(body.credentialStored()).isTrue();
        assertThat(body.hostKeyFingerprint()).isEqualTo("SHA256:abc");
    }

    @Test
    void register_withRejectedCredential_stillRegistersButReportsNotStored() {
        var created = new LanServer("roon", "192.168.3.50", true, 2375, null, null);
        var verification = new net.vaier.domain.SshCredentialVerification(true, false, null);
        when(registerLanServerUseCase.register(eq("roon"), eq("192.168.3.50"), eq(true), eq(2375),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(net.vaier.domain.SshCredentialDraft.class)))
            .thenReturn(new RegisterLanServerUseCase.RegistrationOutcome(created, verification, false));
        var request = new LanServerRestController.RegisterRequest("roon", "192.168.3.50", true, 2375, null, null,
            new LanServerRestController.CredentialBlock("root", "PASSWORD", "wrong", null));

        var response = controller.register(request);

        var body = (LanServerRestController.RegisterResponse) response.getBody();
        assertThat(body.credentialProvided()).isTrue();
        assertThat(body.credentialVerified()).isFalse();
        assertThat(body.credentialStored()).isFalse();
    }

    @Test
    void register_runsDockerTrueWithoutDockerPort_propagatesIllegalArgument() {
        doThrow(new IllegalArgumentException("dockerPort is required"))
            .when(registerLanServerUseCase).register("nas", "192.168.3.50", true, null, null, null);
        var request = new LanServerRestController.RegisterRequest("nas", "192.168.3.50", true, null, null, null);

        assertThatThrownBy(() -> controller.register(request))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void register_propagatesIllegalArgument() {
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(registerLanServerUseCase).register("nas", "10.99.99.99", true, 2375, null, null);
        var request = new LanServerRestController.RegisterRequest("nas", "10.99.99.99", true, 2375, null, null);

        // GlobalExceptionHandler renders 400; the controller must propagate, not swallow.
        assertThatThrownBy(() -> controller.register(request))
            .isInstanceOf(IllegalArgumentException.class);
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
    void downloadSetupScript_conflict_rendersJsonEnvelope_throughTheHandler() throws Exception {
        // With produces="application/x-sh" a JSON/API client (Accept: application/json) couldn't
        // even match the handler, so the 409 ApiError was unreachable (406). Without the constraint
        // the conflict renders as the JSON envelope through the real dispatcher + handler.
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenThrow(new ConflictException("Relay peer apalveien5 has no LAN address set"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/lan-servers/nuc02/setup.sh").accept(MediaType.APPLICATION_JSON))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void downloadSetupScript_propagatesConflictWhenRelayHasNoLanAddress() {
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenThrow(new ConflictException("Relay peer apalveien5 has no LAN address set"));

        assertThatThrownBy(() -> controller.downloadSetupScript("nuc02"))
            .isInstanceOf(ConflictException.class);
    }

    // --- {name}/setup-token (mint) + {name}/setup?t= (token-gated serve) ---

    @Test
    void mintSetupToken_returnsTokenAndTtlInSeconds() {
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenReturn(Optional.of("#!/usr/bin/env bash\n"));
        when(forVendingSetupTokens.issue("nuc02"))
            .thenReturn(SetupToken.issue("nuc02", "s3cret-value", 0L));

        ResponseEntity<?> response = controller.mintSetupToken("nuc02");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LanServerRestController.SetupTokenResponse body =
            (LanServerRestController.SetupTokenResponse) response.getBody();
        assertThat(body.token()).isEqualTo("s3cret-value");
        assertThat(body.expiresInSeconds()).isEqualTo(SetupToken.TTL.toSeconds());
        verify(forVendingSetupTokens).issue("nuc02");
    }

    @Test
    void mintSetupToken_noSetupScript_returns204AndMintsNothing() {
        // A host with nothing to install (no Docker, no LAN to anchor) has no setup script — so there is
        // no command to hand over, and no token is minted.
        when(generateLanServerSetupScriptUseCase.generateSetupScript("printer")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.mintSetupToken("printer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(forVendingSetupTokens, never()).issue(anyString());
    }

    @Test
    void serveTokenizedSetupScript_validToken_returns200TextPlainScript() {
        when(forVendingSetupTokens.consume("nuc02", "good")).thenReturn(true);
        when(generateLanServerSetupScriptUseCase.generateSetupScript("nuc02"))
            .thenReturn(Optional.of("#!/usr/bin/env bash\nip route replace 172.31.16.0/20 via 192.168.3.121\n"));

        ResponseEntity<?> response = controller.serveTokenizedSetupScript("nuc02", "good");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getBody()).asString().contains("ip route replace 172.31.16.0/20 via 192.168.3.121");
    }

    @Test
    void serveTokenizedSetupScript_missingToken_returns401AndServesNothing() {
        ResponseEntity<?> response = controller.serveTokenizedSetupScript("nuc02", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        org.mockito.Mockito.verifyNoInteractions(forVendingSetupTokens);
        org.mockito.Mockito.verifyNoInteractions(generateLanServerSetupScriptUseCase);
    }

    @Test
    void serveTokenizedSetupScript_spentOrInvalidToken_returns401AndServesNothing() {
        when(forVendingSetupTokens.consume("nuc02", "spent")).thenReturn(false);

        ResponseEntity<?> response = controller.serveTokenizedSetupScript("nuc02", "spent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        org.mockito.Mockito.verifyNoInteractions(generateLanServerSetupScriptUseCase);
    }

    @Test
    void serveTokenizedSetupScript_validTokenUnknownMachine_returns404() {
        when(forVendingSetupTokens.consume("ghost", "good")).thenReturn(true);
        when(generateLanServerSetupScriptUseCase.generateSetupScript("ghost")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.serveTokenizedSetupScript("ghost", "good");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
    void rename_propagatesNotFound() {
        doThrow(new NotFoundException("LAN server not found: ghost"))
            .when(renameLanServerUseCase).rename("ghost", "phantom");

        assertThatThrownBy(() -> controller.rename("ghost", new LanServerRestController.RenameRequest("phantom")))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rename_propagatesConflict() {
        doThrow(new ConflictException("A LAN server named printer already exists"))
            .when(renameLanServerUseCase).rename("nas", "printer");

        assertThatThrownBy(() -> controller.rename("nas", new LanServerRestController.RenameRequest("printer")))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void rename_propagatesInvalidName() {
        doThrow(new IllegalArgumentException("New LAN server name must not be blank"))
            .when(renameLanServerUseCase).rename("nas", "  ");

        assertThatThrownBy(() -> controller.rename("nas", new LanServerRestController.RenameRequest("  ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- description (#54) ---

    @Test
    void register_passesDescriptionToUseCase() {
        var request = new LanServerRestController.RegisterRequest(
                "nas", "192.168.3.50", true, 2375, "Synology NAS", null);

        controller.register(request);

        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375, "Synology NAS", null);
    }

    // --- device category ---

    @Test
    void register_passesDeviceCategoryOverrideToUseCase() {
        var request = new LanServerRestController.RegisterRequest(
                "nas", "192.168.3.50", true, 2375, null, "NAS");

        controller.register(request);

        verify(registerLanServerUseCase).register("nas", "192.168.3.50", true, 2375, null,
            net.vaier.domain.DeviceCategory.NAS);
    }

    @Test
    void register_invalidDeviceCategory_propagatesIllegalArgument() {
        var request = new LanServerRestController.RegisterRequest(
                "nas", "192.168.3.50", true, 2375, null, "BANANA");

        assertThatThrownBy(() -> controller.register(request))
            .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(registerLanServerUseCase);
    }

    @Test
    void list_exposesEffectiveDeviceCategoryAndOverrideFlag() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            // No override; name "my-synology" auto-detects to NAS.
            new LanServerView(new LanServer("my-synology", "192.168.3.50", false, null), "apalveien5"),
            // Explicit override to PRINTER, even though name would detect GENERIC.
            new LanServerView(new LanServer("box-9", "192.168.3.20", false, null, null,
                net.vaier.domain.DeviceCategory.PRINTER), "apalveien5")
        ));
        when(reachabilityUseCase.getReachability(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Reachability.UNKNOWN);
        when(getLanServerScrapeUseCase.getLanServerContainers()).thenReturn(List.of());

        var response = controller.list();

        assertThat(response.get(0).deviceCategory()).isEqualTo("NAS");
        assertThat(response.get(0).deviceCategoryOverridden()).isFalse();
        assertThat(response.get(1).deviceCategory()).isEqualTo("PRINTER");
        assertThat(response.get(1).deviceCategoryOverridden()).isTrue();
    }

    @Test
    void updateDeviceCategory_returns204OnSuccess() {
        var response = controller.updateDeviceCategory(
            "nas", new LanServerRestController.UpdateDeviceCategoryRequest("NAS"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanServerDeviceCategoryUseCase).updateDeviceCategory("nas", "NAS");
    }

    @Test
    void updateDeviceCategory_nullBodyIsTreatedAsClear() {
        var response = controller.updateDeviceCategory("nas", null);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanServerDeviceCategoryUseCase).updateDeviceCategory("nas", null);
    }

    @Test
    void updateDeviceCategory_propagatesInvalidValue() {
        doThrow(new IllegalArgumentException("bad category"))
            .when(updateLanServerDeviceCategoryUseCase).updateDeviceCategory("nas", "BANANA");

        assertThatThrownBy(() -> controller.updateDeviceCategory(
            "nas", new LanServerRestController.UpdateDeviceCategoryRequest("BANANA")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDescription_returns204OnSuccess() {
        var response = controller.updateDescription(
                "nas", new LanServerRestController.UpdateDescriptionRequest("Synology in the closet"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(updateLanServerDescriptionUseCase).updateDescription("nas", "Synology in the closet");
    }

    @Test
    void updateDescription_propagatesNotFound() {
        doThrow(new NotFoundException("LAN server not found: ghost"))
            .when(updateLanServerDescriptionUseCase).updateDescription("ghost", "anything");

        assertThatThrownBy(() -> controller.updateDescription(
                "ghost", new LanServerRestController.UpdateDescriptionRequest("anything")))
            .isInstanceOf(NotFoundException.class);
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
