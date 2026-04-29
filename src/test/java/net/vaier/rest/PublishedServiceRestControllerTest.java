package net.vaier.rest;

import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.GetLanServersUseCase;
import net.vaier.application.GetLanServersUseCase.LanServerView;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.ToggleServiceDirectUrlDisabledUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.domain.LanServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishedServiceRestControllerTest {

    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock PublishPeerServiceUseCase publishPeerServiceUseCase;
    @Mock PublishLanServiceUseCase publishLanServiceUseCase;
    @Mock GetPublishableServicesUseCase getPublishableServicesUseCase;
    @Mock DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    @Mock ToggleServiceAuthUseCase toggleServiceAuthUseCase;
    @Mock EditServiceRedirectUseCase editServiceRedirectUseCase;
    @Mock ToggleServiceDirectUrlDisabledUseCase toggleServiceDirectUrlDisabledUseCase;
    @Mock IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;
    @Mock UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;
    @Mock GetLanServersUseCase getLanServersUseCase;
    @Mock SseEventPublisher sseEventPublisher;

    @InjectMocks
    PublishedServiceRestController controller;

    @Test
    void publishLanService_resolvesMachineNameToLanAddress() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "relay")
        ));
        var request = new PublishedServiceRestController.PublishLanRequest(
            "printer-ui", "printer", 9100, "http", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "printer-ui", "192.168.3.20", 9100, "http", false, false);
    }

    @Test
    void publishLanService_unknownMachineName_returns400() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of());
        var request = new PublishedServiceRestController.PublishLanRequest(
            "x", "ghost", 80, "http", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(publishLanServiceUseCase);
    }

    @Test
    void publishLanService_machineRunsDocker_stillResolvesAndPublishes() {
        // A Docker-enabled LAN server can still expose native services that aren't containers
        // (e.g. NAS web UI). Manual publish must work for these too — auto-discovery only covers
        // Docker containers, not host-native services.
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("nas", "192.168.3.50", true, 2375), "relay")
        ));
        var request = new PublishedServiceRestController.PublishLanRequest(
            "nas-ui", "nas", 5000, "https", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "nas-ui", "192.168.3.50", 5000, "https", false, false);
    }

    @Test
    void publishLanService_useCaseThrowsIllegalArgument_returns400() {
        when(getLanServersUseCase.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), null)
        ));
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(publishLanServiceUseCase).publishLanService(
                "printer-ui", "192.168.3.20", 9100, "http", false, false);
        var request = new PublishedServiceRestController.PublishLanRequest(
            "printer-ui", "printer", 9100, "http", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
