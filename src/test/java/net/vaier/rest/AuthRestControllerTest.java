package net.vaier.rest;

import net.vaier.application.CaptureViewerIdentityUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthRestControllerTest {

    @Mock
    ConfigResolver configResolver;

    @Mock
    CaptureViewerIdentityUseCase captureViewerIdentityUseCase;

    @InjectMocks
    AuthRestController controller;

    private AccessEntry entry(String email, Role role, String name) {
        return AccessEntry.builder().email(email).role(role).name(name).build();
    }

    @Test
    void me_returnsIdentityFromOauth2ProxyHeaders() {
        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().username()).isEqualTo("alice");
        assertThat(response.getBody().displayname()).isEqualTo("Alice");
        assertThat(response.getBody().email()).isEqualTo("alice@example.com");
    }

    @Test
    void me_returnsNullUsernameWhenHeaderAbsent() {
        ResponseEntity<AuthRestController.MeResponse> response = controller.getMe(null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().username()).isNull();
    }

    @Test
    void me_isAdminTrue_whenTheResolvedAccessEntryIsAdmin() {
        when(captureViewerIdentityUseCase.captureIdentity("alice@example.com", "Alice", null, null))
                .thenReturn(Optional.of(entry("alice@example.com", Role.ADMIN, "Alice")));

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com", null, null);

        assertThat(response.getBody().isAdmin()).isTrue();
    }

    @Test
    void me_isAdminFalse_whenTheResolvedAccessEntryIsAnOrdinaryUser() {
        when(captureViewerIdentityUseCase.captureIdentity("bob@example.com", "Bob", null, null))
                .thenReturn(Optional.of(entry("bob@example.com", Role.USER, "Bob")));

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("bob", "Bob", "bob@example.com", null, null);

        assertThat(response.getBody().isAdmin()).isFalse();
    }

    @Test
    void me_isAdminFalse_whenTheIdentityIsUnknownToTheAccessStore() {
        when(captureViewerIdentityUseCase.captureIdentity("stranger@example.com", "Stranger", null, null))
                .thenReturn(Optional.empty());

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("stranger", "Stranger", "stranger@example.com", null, null);

        assertThat(response.getBody().isAdmin()).isFalse();
    }

    @Test
    void me_capturesTheViewersIdentitySoTheAdminCardFillsInFromTheLaunchpadAlone() {
        // The launchpad's /users/me is a viewer's main authenticated touch-point — it never crosses
        // the /authz/verify forward-auth path. Capturing here persists the name + last-used provider
        // for a user who only ever uses the launchpad, so their admin Users card is no longer blank.
        when(captureViewerIdentityUseCase.captureIdentity("turid@example.com", "Turid", "google", "sub-1"))
                .thenReturn(Optional.of(AccessEntry.builder()
                        .email("turid@example.com").role(Role.USER).name("Turid")
                        .provider("google").providerUserId("sub-1").build()));

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("turid", "Turid", "turid@example.com", "google", "sub-1");

        verify(captureViewerIdentityUseCase).captureIdentity("turid@example.com", "Turid", "google", "sub-1");
        assertThat(response.getBody().provider()).isEqualTo("google");
        assertThat(response.getBody().providerUserId()).isEqualTo("sub-1");
    }

    @Test
    void me_carriesTheResolvedViewersProviderAndProviderUserId() {
        // The topbar avatar reuses the Users-card photo chain (GitHub id → Gravatar), so getMe must
        // surface the viewer's captured provider identity.
        when(captureViewerIdentityUseCase.captureIdentity("alice@example.com", "Alice", "github", "12345"))
                .thenReturn(Optional.of(AccessEntry.builder()
                        .email("alice@example.com").role(Role.ADMIN).name("Alice")
                        .provider("github").providerUserId("12345").build()));

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com", "github", "12345");

        assertThat(response.getBody().provider()).isEqualTo("github");
        assertThat(response.getBody().providerUserId()).isEqualTo("12345");
    }

    @Test
    void me_providerAndProviderUserIdNull_whenViewerAbsent() {
        when(captureViewerIdentityUseCase.captureIdentity("stranger@example.com", "Stranger", null, null))
                .thenReturn(Optional.empty());

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("stranger", "Stranger", "stranger@example.com", null, null);

        assertThat(response.getBody().provider()).isNull();
        assertThat(response.getBody().providerUserId()).isNull();
    }

    @Test
    void me_displayName_fallsBackToStoredEntryNameWhenHeaderNameAbsent() {
        when(captureViewerIdentityUseCase.captureIdentity("alice@example.com", null, null, null))
                .thenReturn(Optional.of(entry("alice@example.com", Role.ADMIN, "Alice Stored")));

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", null, "alice@example.com", null, null);

        assertThat(response.getBody().displayname()).isEqualTo("Alice Stored");
    }

    @Test
    void me_logoutUrl_alwaysPointsAtOauth2ProxySignOut() {
        // The console is always on social login, so logout always ends the session via oauth2-proxy.
        when(configResolver.getDomain()).thenReturn("example.com");

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com", null, null);

        assertThat(response.getBody().logoutUrl())
                .isEqualTo("https://oauth2.example.com/oauth2/sign_out?rd=https%3A%2F%2Fvaier.example.com%2F");
    }

    @Test
    void me_loginUrl_pointsAtTheConsole_whichForcesSignInWhenAnonymous() {
        when(configResolver.getDomain()).thenReturn("example.com");

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe(null, null, null, null, null);

        assertThat(response.getBody().loginUrl()).isEqualTo("https://vaier.example.com/");
    }

    @Test
    void me_returnsNullLogoutAndLoginWhenDomainMissing() {
        when(configResolver.getDomain()).thenReturn(null);

        ResponseEntity<AuthRestController.MeResponse> response =
                controller.getMe("alice", "Alice", "alice@example.com", null, null);

        assertThat(response.getBody().logoutUrl()).isNull();
        assertThat(response.getBody().loginUrl()).isNull();
    }
}
