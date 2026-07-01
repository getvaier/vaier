package net.vaier.rest;

import net.vaier.config.ConfigResolver;
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

    @InjectMocks
    AuthRestController controller;

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

    @Test
    void me_logoutUrl_pointsAtAutheliaPortalWhenConsoleModeIsAuthelia() {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(configResolver.getConsoleAuthMode()).thenReturn(net.vaier.domain.AuthMode.AUTHELIA);

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com");

        assertThat(response.getBody().logoutUrl())
                .isEqualTo("https://login.example.com/logout?rd=https://vaier.example.com/");
    }

    @Test
    void me_logoutUrl_pointsAtOauth2ProxySignOutWhenConsoleModeIsSocial() {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(configResolver.getConsoleAuthMode()).thenReturn(net.vaier.domain.AuthMode.SOCIAL);

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com");

        assertThat(response.getBody().logoutUrl())
                .startsWith("https://oauth2.example.com/oauth2/sign_out?rd=");
    }
}
