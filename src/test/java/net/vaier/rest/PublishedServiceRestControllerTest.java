package net.vaier.rest;

import net.vaier.adapter.driven.SseEventPublisher;
import net.vaier.application.DeletePublishedServiceUseCase;
import net.vaier.application.EditServiceRedirectUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.IgnorePublishableServiceUseCase;
import net.vaier.application.PublishLanServiceUseCase;
import net.vaier.application.PublishPeerServiceUseCase;
import net.vaier.application.ToggleServiceAuthUseCase;
import net.vaier.application.ToggleServiceDirectUrlDisabledUseCase;
import net.vaier.application.UnignorePublishableServiceUseCase;
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
    @Mock ToggleServiceAuthUseCase toggleServiceAuthUseCase;
    @Mock EditServiceRedirectUseCase editServiceRedirectUseCase;
    @Mock ToggleServiceDirectUrlDisabledUseCase toggleServiceDirectUrlDisabledUseCase;
    @Mock IgnorePublishableServiceUseCase ignorePublishableServiceUseCase;
    @Mock UnignorePublishableServiceUseCase unignorePublishableServiceUseCase;
    @Mock SseEventPublisher sseEventPublisher;

    @InjectMocks
    PublishedServiceRestController controller;

    @Test
    void publishLanService_validRequest_delegatesToUseCase() {
        var request = new PublishedServiceRestController.PublishLanRequest(
            "nas", "192.168.3.50", 5000, "https", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "nas", "192.168.3.50", 5000, "https", false, false);
    }

    @Test
    void publishLanService_useCaseThrowsIllegalArgument_returns400() {
        doThrow(new IllegalArgumentException("not in any lanCidr"))
            .when(publishLanServiceUseCase).publishLanService(
                "nas", "10.99.99.99", 5000, "http", false, false);
        var request = new PublishedServiceRestController.PublishLanRequest(
            "nas", "10.99.99.99", 5000, "http", false, false);

        ResponseEntity<Void> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
