package net.vaier.rest;

import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
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
class AuthzRestControllerTest {

    @Mock VerifyAccessUseCase verifyAccessUseCase;
    @Mock ListAccessEntriesUseCase listAccessEntriesUseCase;
    @Mock GrantRoleUseCase grantRoleUseCase;
    @Mock AssignGroupsUseCase assignGroupsUseCase;
    @Mock RevokeAccessUseCase revokeAccessUseCase;

    @InjectMocks AuthzRestController controller;

    // --- GET /authz/verify (forward-auth) ---

    @Test
    void verify_allowed_returns200WithIdentityHeaders() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).groups(List.of("family", "media")).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify("friend@example.com", "plex.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Remote-User")).isEqualTo("friend@example.com");
        assertThat(response.getHeaders().getFirst("Remote-Email")).isEqualTo("friend@example.com");
        assertThat(response.getHeaders().getFirst("Remote-Groups")).isEqualTo("family,media");
    }

    @Test
    void verify_denied_returns403WithBrandedHtmlPage() {
        when(verifyAccessUseCase.verify("p@example.com", "plex.example.com", null))
                .thenReturn(AccessDecision.deny());

        ResponseEntity<String> response = controller.verify("p@example.com", "plex.example.com", null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // Forward-auth returns this body to the browser, so it must be the branded Vaier page.
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(response.getBody()).contains("Vaier");
        assertThat(response.getBody().toLowerCase()).contains("approval");
    }

    @Test
    void verify_passesTheDisplayNameHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith"))
                .thenReturn(AccessDecision.deny());

        controller.verify("friend@example.com", "plex.example.com", "Alice Smith");

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith");
    }

    // --- GET /access (admin) ---

    @Test
    void listAccess_mapsEntriesToDtosWithLowercaseRole() {
        when(listAccessEntriesUseCase.listAccessEntries()).thenReturn(List.of(
                AccessEntry.builder().email("a@example.com").role(Role.ADMIN).groups(List.of("admins")).build(),
                AccessEntry.builder().email("b@example.com").role(Role.PENDING).groups(List.of()).build()));

        List<AuthzRestController.AccessEntryResponse> result = controller.listAccess();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).email()).isEqualTo("a@example.com");
        assertThat(result.get(0).role()).isEqualTo("admin");
        assertThat(result.get(0).groups()).containsExactly("admins");
        assertThat(result.get(1).role()).isEqualTo("pending");
    }

    @Test
    void listAccess_includesTheDisplayName() {
        when(listAccessEntriesUseCase.listAccessEntries()).thenReturn(List.of(
                AccessEntry.builder().email("a@example.com").role(Role.ADMIN).groups(List.of("admins")).name("Alice Smith").build(),
                AccessEntry.builder().email("b@example.com").role(Role.PENDING).groups(List.of()).build()));

        List<AuthzRestController.AccessEntryResponse> result = controller.listAccess();

        assertThat(result.get(0).name()).isEqualTo("Alice Smith");
        assertThat(result.get(1).name()).isNull();
    }

    // --- PATCH /access/{email}/role ---

    @Test
    void grantRole_delegatesToUseCaseWithParsedRole() {
        ResponseEntity<String> response = controller.grantRole("p@example.com",
                new AuthzRestController.GrantRoleRequest("user"));

        verify(grantRoleUseCase).grantRole("p@example.com", Role.USER);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // --- PATCH /access/{email}/groups ---

    @Test
    void assignGroups_delegatesToUseCase() {
        ResponseEntity<String> response = controller.assignGroups("u@example.com",
                new AuthzRestController.AssignGroupsRequest(List.of("family", "media")));

        verify(assignGroupsUseCase).assignGroups("u@example.com", List.of("family", "media"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // --- DELETE /access/{email} ---

    @Test
    void revokeAccess_delegatesToUseCase() {
        ResponseEntity<String> response = controller.revokeAccess("gone@example.com");

        verify(revokeAccessUseCase).revokeAccess("gone@example.com");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
