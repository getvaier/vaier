package net.vaier.rest;

import net.vaier.application.ApproveEnrolmentUseCase;
import net.vaier.application.ApproveEnrolmentUseCase.ApprovedEnrolmentUco;
import net.vaier.application.EnrolDeviceUseCase.EnrolledDeviceUco;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.LookUpEnrolmentTicketUseCase;
import net.vaier.application.NotifyAdminsOfEnrolmentRequestUseCase;
import net.vaier.application.RefuseEnrolmentUseCase;
import net.vaier.application.RequestEnrolmentUseCase;
import net.vaier.domain.ConflictException;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.EnrolmentVerdict;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForSubscribingToEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The join-code flow (#359 slice 1b) seen from the wire. Two of these routes are anonymous, so what
 * they may and may not say is the whole point: the POST hands back a code and a ticket and nothing
 * about the fleet, and a stream keyed by a ticket nobody holds is simply gone.
 */
@ExtendWith(MockitoExtension.class)
class EnrolmentRequestRestControllerTest {

    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String TICKET = "ZmFrZS10aWNrZXQtdmFsdWUtZm9yLXRoZS10ZXN0cy0xMjM";
    private static final String CONFIG = "# VAIER: {}\n[Interface]\nAddress = 10.13.13.7/32\n";

    @Mock RequestEnrolmentUseCase requestEnrolmentUseCase;
    @Mock ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    @Mock ApproveEnrolmentUseCase approveEnrolmentUseCase;
    @Mock RefuseEnrolmentUseCase refuseEnrolmentUseCase;
    @Mock LookUpEnrolmentTicketUseCase lookUpEnrolmentTicketUseCase;
    @Mock NotifyAdminsOfEnrolmentRequestUseCase notifyAdminsOfEnrolmentRequestUseCase;
    @Mock ForPublishingEvents forPublishingEvents;
    @Mock ForSubscribingToEvents forSubscribingToEvents;

    @InjectMocks EnrolmentRequestRestController controller;

    private static EnrolmentRequest waiting(String code, String ticket) {
        return EnrolmentRequest.open("Ruten", DEVICE_KEY, null, code, ticket, System.currentTimeMillis());
    }

    private static String encoded(String config) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(config.getBytes(StandardCharsets.UTF_8));
    }

    // --- POST /vpn/enrolments (anonymous) ---

    @Test
    void request_handsBackTheCodeToShowAndTheTicketToHold() {
        when(requestEnrolmentUseCase.request("Ruten", DEVICE_KEY, "windows")).thenReturn(waiting("4821", TICKET));

        var response = controller.request(
            new EnrolmentRequestRestController.RequestEnrolmentRequest("Ruten", DEVICE_KEY, "windows"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().code()).isEqualTo("4821");
        assertThat(response.getBody().ticket()).isEqualTo(TICKET);
        assertThat(response.getBody().expiresInSeconds()).isBetween(590L, 600L);
    }

    @Test
    void requestResponse_saysNothingAboutTheFleet() {
        // The only anonymous write in Vaier. Everything it returns is about the caller's own request.
        assertThat(EnrolmentRequestRestController.RequestEnrolmentResponse.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("code", "ticket", "expiresInSeconds");
    }

    @Test
    void request_tellsEverySignedInOperatorAPhoneIsWaiting() {
        when(requestEnrolmentUseCase.request("Ruten", DEVICE_KEY, null)).thenReturn(waiting("4821", TICKET));

        controller.request(new EnrolmentRequestRestController.RequestEnrolmentRequest("Ruten", DEVICE_KEY, null));

        verify(forPublishingEvents).publish("enrolment-requests", "requested", "4821");
    }

    @Test
    void request_doesNotJudgeTheKeyItself_theDomainDoes() {
        when(requestEnrolmentUseCase.request("Ruten", "not-a-key", null))
            .thenThrow(new IllegalArgumentException("WireGuard key must be a 32-byte base64 key"));

        assertThatThrownBy(() -> controller.request(
                new EnrolmentRequestRestController.RequestEnrolmentRequest("Ruten", "not-a-key", null)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(forPublishingEvents);
    }

    @Test
    void request_lettingTheConflictThrough_whenTooManyAreAlreadyWaiting() {
        when(requestEnrolmentUseCase.request("Ruten", DEVICE_KEY, null))
            .thenThrow(new ConflictException("Too many devices are already waiting to join."));

        assertThatThrownBy(() -> controller.request(
                new EnrolmentRequestRestController.RequestEnrolmentRequest("Ruten", DEVICE_KEY, null)))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(forPublishingEvents);
    }

    // --- GET /vpn/enrolments (admin) ---

    @Test
    void pending_showsTheOperatorWhichPhoneIsWhich() {
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(waiting("4821", TICKET)));

        var body = controller.pending().getBody();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).code()).isEqualTo("4821");
        assertThat(body.get(0).name()).isEqualTo("Ruten");
        assertThat(body.get(0).publicKey()).isEqualTo(DEVICE_KEY);
        assertThat(body.get(0).machineType()).isEqualTo("MOBILE_CLIENT");
        assertThat(body.get(0).expiresInSeconds()).isBetween(590L, 600L);
    }

    @Test
    void pendingResponse_neverCarriesTheTicket() {
        // The ticket gates delivery of a config. It is handed to the phone once and must never reach
        // any other screen, browser or log — including an admin's.
        assertThat(EnrolmentRequestRestController.PendingEnrolmentResponse.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("code", "name", "publicKey", "machineType", "expiresInSeconds");
    }

    // --- GET /vpn/enrolments/{ticket}/events (anonymous, ticket-gated) ---

    @Test
    void ticketEvents_aTicketNobodyHolds_isGone() {
        when(lookUpEnrolmentTicketUseCase.lookUp("made-up")).thenReturn(EnrolmentVerdict.gone());

        var response = controller.ticketEvents("made-up");

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(forSubscribingToEvents, forPublishingEvents);
    }

    @Test
    void ticketEvents_aWaitingPhone_listensOnItsOwnStreamAndNobodyElses() {
        SseEmitter emitter = new SseEmitter();
        when(lookUpEnrolmentTicketUseCase.lookUp(TICKET)).thenReturn(EnrolmentVerdict.pending());
        when(forSubscribingToEvents.subscribe("enrolment:" + TICKET)).thenReturn(emitter);

        var response = controller.ticketEvents(TICKET);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(emitter);
        verifyNoInteractions(forPublishingEvents);
    }

    @Test
    void ticketEvents_aPhoneWhoseStreamDroppedMidApproval_isServedAgainAtOnce() {
        SseEmitter emitter = new SseEmitter();
        when(lookUpEnrolmentTicketUseCase.lookUp(TICKET)).thenReturn(EnrolmentVerdict.approved(CONFIG));
        when(forSubscribingToEvents.subscribe("enrolment:" + TICKET)).thenReturn(emitter);

        var response = controller.ticketEvents(TICKET);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Base64url so the multi-line config rides in one SSE data field — the same encoding the app
        // already parses.
        verify(forPublishingEvents).publish("enrolment:" + TICKET, "approved", encoded(CONFIG));
    }

    // --- POST /vpn/enrolments/{code}/approve (admin) ---

    private ApprovedEnrolmentUco approvedRuten() {
        return new ApprovedEnrolmentUco(TICKET, new EnrolledDeviceUco("ruten",
            TestMachineIds.of("ruten"), "Ruten", "10.13.13.7", DEVICE_KEY, CONFIG,
            MachineType.MOBILE_CLIENT));
    }

    @Test
    void approve_answersTheOperatorWithTheNewPeer_andNeverWithTheConfig() {
        // The config belongs to the phone and travels on the phone's own stream. An operator
        // approving from a borrowed laptop must not end up holding it.
        when(approveEnrolmentUseCase.approve("4821")).thenReturn(approvedRuten());

        var response = controller.approve("4821");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().id()).isEqualTo("ruten");
        assertThat(response.getBody().machineId()).isEqualTo(TestMachineIds.of("ruten").value());
        assertThat(response.getBody().name()).isEqualTo("Ruten");
        assertThat(response.getBody().ipAddress()).isEqualTo("10.13.13.7");
        assertThat(response.getBody().machineType()).isEqualTo("MOBILE_CLIENT");
        assertThat(EnrolmentRequestRestController.ApprovedEnrolmentResponse.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("id", "machineId", "name", "ipAddress", "machineType");
    }

    @Test
    void approve_tellsThePhone_theFleetAndTheOtherOperators() {
        when(approveEnrolmentUseCase.approve("4821")).thenReturn(approvedRuten());

        controller.approve("4821");

        verify(forPublishingEvents).publish("enrolment:" + TICKET, "approved", encoded(CONFIG));
        verify(forPublishingEvents).publish("vpn-peers", "peers-updated", "");
        verify(forPublishingEvents).publish("enrolment-requests", "approved", "4821");
    }

    @Test
    void approve_anUnknownCode_isNotFound_andTellsNobodyAnything() {
        when(approveEnrolmentUseCase.approve("0000"))
            .thenThrow(new NotFoundException("No device is waiting with join code 0000"));

        assertThatThrownBy(() -> controller.approve("0000")).isInstanceOf(NotFoundException.class);

        verify(forPublishingEvents, never()).publish(anyString(), anyString(), any());
    }

    // --- DELETE /vpn/enrolments/{code} (admin) ---

    @Test
    void refuse_closesThePhonesStreamAndClearsTheOperatorsList() {
        when(refuseEnrolmentUseCase.refuse("4821")).thenReturn(Optional.of(waiting("4821", TICKET)));

        var response = controller.refuse("4821");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(forPublishingEvents).publish("enrolment:" + TICKET, "refused", "");
        verify(forPublishingEvents).publish("enrolment-requests", "refused", "4821");
    }

    @Test
    void refuse_anUnknownCode_isStillNoContent() {
        when(refuseEnrolmentUseCase.refuse("0000")).thenReturn(Optional.empty());

        assertThat(controller.refuse("0000").getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(forPublishingEvents);
    }

    // --- GET /vpn/enrolments/events (admin) ---

    @Test
    void adminEvents_listensForWaitingPhones() {
        SseEmitter emitter = new SseEmitter();
        when(forSubscribingToEvents.subscribe("enrolment-requests")).thenReturn(emitter);

        assertThat(controller.adminEvents()).isSameAs(emitter);
    }

    @Test
    void request_mailsTheAdmins_becauseAPhoneIsWaitingOnAPerson() {
        EnrolmentRequest opened = EnrolmentRequest.open("Ruten", DEVICE_KEY, null, "4821", TICKET,
            System.currentTimeMillis());
        when(requestEnrolmentUseCase.request("Ruten", DEVICE_KEY, null)).thenReturn(opened);

        controller.request(new EnrolmentRequestRestController.RequestEnrolmentRequest("Ruten", DEVICE_KEY, null));

        verify(notifyAdminsOfEnrolmentRequestUseCase).notifyAdminsOfEnrolmentRequest(opened);
    }
}
