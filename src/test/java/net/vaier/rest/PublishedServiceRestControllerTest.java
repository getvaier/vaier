package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.GetOwnSignInsUseCase;
import net.vaier.application.MarkMeantToBePublicUseCase;
import net.vaier.domain.MachineId;
import net.vaier.domain.OwnSignIn;
import net.vaier.domain.ServiceOwnSignIn;
import net.vaier.domain.ServiceProbeAnswer;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
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

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForSubscribingToEvents forSubscribingToEvents;
    @Mock GetOwnSignInsUseCase getOwnSignInsUseCase;
    @Mock MarkMeantToBePublicUseCase markMeantToBePublicUseCase;

    @InjectMocks
    PublishedServiceRestController controller;

    @Test
    void signIns_sayWhatEachServiceAsksForByItself() throws Exception {
        OwnSignIn basic = OwnSignIn.classify(Optional.of(new ServiceProbeAnswer(401, "Basic realm=\"openHAB\"",
            null, "text/html", "")), Instant.parse("2026-09-23T10:00:00Z"));
        when(getOwnSignInsUseCase.getOwnSignIns())
            .thenReturn(List.of(new ServiceOwnSignIn("openhab.example.com", null, basic, OwnSignIn.Advice.SET_SERVICE_CREDENTIAL,
                false, false)));

        String json = new ObjectMapper().writeValueAsString(controller.signIns());

        assertThat(json).isEqualTo("[{\"dnsName\":\"openhab.example.com\",\"pathPrefix\":null,\"kind\":\"basic\","
            + "\"detail\":\"openHAB\",\"app\":null,\"observedAt\":\"2026-09-23T10:00:00Z\","
            + "\"advice\":\"set_service_credential\",\"open\":false,\"meantToBePublic\":false}]");
    }

    @Test
    void meantToBePublic_handsTheRouteAndTheAnswerToTheUseCase() {
        controller.meantToBePublic("rack.example.com", "/ui", new PublishedServiceRestController.MeantToBePublicRequest(true));

        verify(markMeantToBePublicUseCase).markMeantToBePublic("rack.example.com", "/ui", true);
    }

    @Test
    void subscribeToEvents_subscribesToPublishedServicesTopicViaPort() {
        SseEmitter emitter = new SseEmitter();
        when(forSubscribingToEvents.subscribe("published-services")).thenReturn(emitter);

        SseEmitter result = controller.subscribeToEvents();

        assertThat(result).isSameAs(emitter);
        verify(forSubscribingToEvents).subscribe("published-services");
    }

    @Test
    void publishLanService_forwardsTheMachineIdentityToUseCase() {
        MachineId printer = TestMachineIds.of("printer");
        var request = new PublishedServiceRestController.PublishLanRequest(
            "printer-ui", printer.value(), 9100, "http", false, false, null, null);

        ResponseEntity<?> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "printer-ui", printer, 9100, "http", false, false, null, null);
    }

    @Test
    void publishLanService_forwardsRootRedirectPathToUseCase() {
        MachineId rig = TestMachineIds.of("rig");
        var request = new PublishedServiceRestController.PublishLanRequest(
            "app", rig.value(), 3000, "http", false, false, "/builder/ui/", null);

        ResponseEntity<?> response = controller.publishLanService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishLanServiceUseCase).publishLanService(
            "app", rig, 3000, "http", false, false, "/builder/ui/", null);
    }

    @Test
    void publishLanService_useCaseThrowsIllegalArgument_propagatesToGlobalHandler() {
        MachineId ghost = TestMachineIds.of("ghost");
        doThrow(new IllegalArgumentException("Unknown machine: " + ghost.value()))
            .when(publishLanServiceUseCase).publishLanService(
                "x", ghost, 80, "http", false, false, null, null);
        var request = new PublishedServiceRestController.PublishLanRequest(
            "x", ghost.value(), 80, "http", false, false, null, null);

        // The controller no longer hand-rolls a 400 body; the validation exception
        // propagates to GlobalExceptionHandler, which renders the uniform ApiError 400.
        assertThatThrownBy(() -> controller.publishLanService(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown machine: " + ghost.value());
    }

    @Test
    void publishService_useCaseThrowsIllegalArgument_propagatesToGlobalHandler() {
        doThrow(new IllegalArgumentException("A route already exists on app.example.com"))
            .when(publishPeerServiceUseCase).publishService(
                "10.13.13.2", 8080, "app", false, null, false, null, false);
        var request = new PublishedServiceRestController.PublishRequest(
            "10.13.13.2", 8080, "app", false, null, false, null, false);

        assertThatThrownBy(() -> controller.publishService(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A route already exists on app.example.com");
    }
    @Test
    void publishService_carriesTheStreamIntentThroughToTheUseCase() {
        // The one intent question the operator answers in the dialog — website, or raw TCP — is a
        // field on the request, not a second endpoint.
        var request = new PublishedServiceRestController.PublishRequest(
            "172.20.0.1", 1883, "mqtt", false, null, false, null, true);

        ResponseEntity<?> response = controller.publishService(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(publishPeerServiceUseCase).publishService(
            "172.20.0.1", 1883, "mqtt", false, null, false, null, true);
    }

    @Test
    void publishService_withoutTheStreamFlag_publishesAnHttpService() {
        var request = new PublishedServiceRestController.PublishRequest(
            "10.13.13.2", 8080, "app", false, null, false, null, false);

        controller.publishService(request);

        verify(publishPeerServiceUseCase).publishService(
            "10.13.13.2", 8080, "app", false, null, false, null, false);
    }
}
