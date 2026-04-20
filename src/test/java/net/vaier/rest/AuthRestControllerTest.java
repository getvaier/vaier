package net.vaier.rest;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.port.ForPersistingUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthRestControllerTest {

    @Mock
    ConfigResolver configResolver;

    @Mock
    ForPersistingUsers forPersistingUsers;

    @Mock
    AddUserUseCase addUserUseCase;

    @Mock
    DeleteUserUseCase deleteUserUseCase;

    @Mock
    ChangePasswordUseCase changePasswordUseCase;

    @InjectMocks
    AuthRestController controller;

    @Test
    void changePassword_delegatesToUseCase() {
        ResponseEntity<String> response = controller.changePassword("alice",
                new AuthRestController.ChangePasswordRequest("newpassword"));

        verify(changePasswordUseCase).changePassword("alice", "newpassword");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void changePassword_returns400WhenUseCaseFails() {
        doThrow(new RuntimeException("User not found: alice"))
                .when(changePasswordUseCase).changePassword(eq("alice"), any());

        ResponseEntity<String> response = controller.changePassword("alice",
                new AuthRestController.ChangePasswordRequest("newpassword"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void me_returnsUsernameFromRemoteUserHeader() {
        ResponseEntity<AuthRestController.MeResponse> response = controller.getMe("alice", "Alice", "alice@example.com");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().username()).isEqualTo("alice");
        assertThat(response.getBody().displayname()).isEqualTo("Alice");
        assertThat(response.getBody().email()).isEqualTo("alice@example.com");
    }

    @Test
    void me_returnsNullUsernameWhenHeaderAbsent() {
        ResponseEntity<AuthRestController.MeResponse> response = controller.getMe(null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().username()).isNull();
    }
}
