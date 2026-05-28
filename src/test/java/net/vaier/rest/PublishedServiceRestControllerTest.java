package net.vaier.rest;

import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
import net.vaier.application.UpdatePublishedServiceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PublishedServiceRestControllerTest {

    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock PublishPeerServiceUseCase publishPeerServiceUseCase;
    @Mock PublishLanServiceUseCase publishLanServiceUseCase;
    @Mock GetPublishableServicesUseCase getPublishableServicesUseCase;
    @Mock DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    @Mock UpdatePublishedServiceUseCase updatePublishedServiceUseCase;
    @Mock IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;
    @Mock UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;
    @Mock SseEventPublisher sseEventPublisher;

    @InjectMocks
    PublishedServiceRestController controller;

    @Test
    void publishLanService_forwardsMachineNameVerbatimToUseCase() {
        var request = new PublishedServiceRestController.PublishLanRequest(
            "printer-ui", "printer", 9100, "http", false, false, null, null);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "printer-ui", "printer", 9100, "http", false, false, null, null);
    }

    @Test
    void publishLanService_forwardsRootRedirectPathToUseCase() {
        var request = new PublishedServiceRestController.PublishLanRequest(
            "app", "rig", 3000, "http", false, false, "/builder/ui/", null);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "app", "rig", 3000, "http", false, false, "/builder/ui/", null);
    }

    @Test
    void publishLanService_useCaseThrowsIllegalArgument_returns400() {
        doThrow(new IllegalArgumentException("Unknown machine: ghost"))
            .when(publishLanServiceUseCase).publishLanService(
                "x", "ghost", 80, "http", false, false, null, null);
        var request = new PublishedServiceRestController.PublishLanRequest(
            "x", "ghost", 80, "http", false, false, null, null);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
