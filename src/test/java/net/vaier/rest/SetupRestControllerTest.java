package net.vaier.rest;

import net.vaier.application.CheckSetupStatusUseCase;
import net.vaier.application.CompleteSetupUseCase;
import net.vaier.application.ValidateAwsCredentialsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetupRestControllerTest {

    @Mock CheckSetupStatusUseCase checkSetupStatusUseCase;
    @Mock ValidateAwsCredentialsUseCase validateAwsCredentialsUseCase;
    @Mock CompleteSetupUseCase completeSetupUseCase;
    @InjectMocks SetupRestController controller;

    @Test
    void status_returnsConfiguredState() {
        when(checkSetupStatusUseCase.isConfigured()).thenReturn(false);

        var response = controller.status();

        assertThat(response.getBody().configured()).isFalse();
    }

    @Test
    void validateAws_returnsZones() {
        when(validateAwsCredentialsUseCase.validateAndListZones("key", "secret"))
            .thenReturn(List.of("example.com"));

        var request = new SetupRestController.ValidateAwsRequest("key", "secret");
        var response = controller.validateAws(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((SetupRestController.ValidateAwsResponse) response.getBody()).zones())
            .containsExactly("example.com");
    }

    @Test
    void validateAws_returns400OnError() {
        when(validateAwsCredentialsUseCase.validateAndListZones("bad", "creds"))
            .thenThrow(new RuntimeException("Invalid credentials"));

        var request = new SetupRestController.ValidateAwsRequest("bad", "creds");
        var response = controller.validateAws(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void complete_returns200OnSuccess() {
        var request = new SetupRestController.CompleteSetupRequest(
            "example.com", "key", "secret", "admin@example.com", "admin", "password"
        );

        var response = controller.complete(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(completeSetupUseCase).completeSetup(
            "example.com", "key", "secret", "admin@example.com", "admin", "password"
        );
    }

    @Test
    void complete_returns409WhenAlreadyConfigured() {
        doThrow(new IllegalStateException("Already configured"))
            .when(completeSetupUseCase).completeSetup(any(), any(), any(), any(), any(), any());

        var request = new SetupRestController.CompleteSetupRequest(
            "example.com", "key", "secret", "admin@example.com", "admin", "password"
        );

        var response = controller.complete(request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
