package net.vaier.rest;

import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.GetServiceAccessRulesUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.SetServiceAccessRuleUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.LastAdminException;
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
    @Mock SetServiceAccessRuleUseCase setServiceAccessRuleUseCase;
    @Mock GetServiceAccessRulesUseCase getServiceAccessRulesUseCase;

    @InjectMocks AuthzRestController controller;

    // --- GET /authz/verify (forward-auth) ---

    @Test
    void verify_allowed_returns200WithIdentityHeaders() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).groups(List.of("family", "media")).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify("friend@example.com", "plex.example.com", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Remote-User")).isEqualTo("friend@example.com");
        assertThat(response.getHeaders().getFirst("Remote-Email")).isEqualTo("friend@example.com");
        assertThat(response.getHeaders().getFirst("Remote-Groups")).isEqualTo("family,media");
    }

    @Test
    void verify_allowed_emitsRemoteNameHeaderWhenTheEntryHasADisplayName() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).name("Alice Smith").build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify("friend@example.com", "plex.example.com", "Alice Smith", null, null);

        assertThat(response.getHeaders().getFirst("Remote-Name")).isEqualTo("Alice Smith");
    }

    @Test
    void verify_allowed_omitsRemoteNameHeaderWhenTheEntryHasNoDisplayName() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify("friend@example.com", "plex.example.com", null, null, null);

        // Pre-approved entries have no name yet — don't emit an empty header.
        assertThat(response.getHeaders().containsKey("Remote-Name")).isFalse();
    }

    @Test
    void verify_denied_returns403WithBrandedHtmlPage() {
        when(verifyAccessUseCase.verify("p@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.deny());

        ResponseEntity<String> response = controller.verify("p@example.com", "plex.example.com", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // Forward-auth returns this body to the browser, so it must be the branded Vaier page.
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(response.getBody()).contains("Vaier");
        assertThat(response.getBody().toLowerCase()).contains("approval");
    }

    @Test
    void verify_passesTheDisplayNameHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", null, null))
                .thenReturn(AccessDecision.deny());

        controller.verify("friend@example.com", "plex.example.com", "Alice Smith", null, null);

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", null, null);
    }

    @Test
    void verify_passesTheConnectorHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", null))
                .thenReturn(AccessDecision.deny());

        controller.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", null);

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", "github", null);
    }

    @Test
    void verify_passesTheConnectorUidHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", "98765"))
                .thenReturn(AccessDecision.deny());

        controller.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", "98765");

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", "github", "98765");
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

    @Test
    void listAccess_includesTheLastSignInProvider() {
        when(listAccessEntriesUseCase.listAccessEntries()).thenReturn(List.of(
                AccessEntry.builder().email("a@example.com").role(Role.ADMIN).groups(List.of("admins")).provider("github").build(),
                AccessEntry.builder().email("b@example.com").role(Role.PENDING).groups(List.of()).build()));

        List<AuthzRestController.AccessEntryResponse> result = controller.listAccess();

        assertThat(result.get(0).provider()).isEqualTo("github");
        assertThat(result.get(1).provider()).isNull();
    }

    @Test
    void listAccess_includesTheProviderUserId() {
        when(listAccessEntriesUseCase.listAccessEntries()).thenReturn(List.of(
                AccessEntry.builder().email("a@example.com").role(Role.ADMIN).groups(List.of("admins")).providerUserId("98765").build(),
                AccessEntry.builder().email("b@example.com").role(Role.PENDING).groups(List.of()).build()));

        List<AuthzRestController.AccessEntryResponse> result = controller.listAccess();

        assertThat(result.get(0).providerUserId()).isEqualTo("98765");
        assertThat(result.get(1).providerUserId()).isNull();
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

    // --- PUT /access/services/{host}/groups (set a per-service access rule) ---

    @Test
    void setServiceAccessRule_delegatesToUseCase() {
        ResponseEntity<String> response = controller.setServiceAccessRule("plex.example.com",
                new AuthzRestController.ServiceAccessRuleRequest(List.of("family", "media")));

        verify(setServiceAccessRuleUseCase).setAllowedGroups("plex.example.com", List.of("family", "media"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void setServiceAccessRule_emptyGroupsClearsTheRule() {
        controller.setServiceAccessRule("plex.example.com",
                new AuthzRestController.ServiceAccessRuleRequest(List.of()));

        verify(setServiceAccessRuleUseCase).setAllowedGroups("plex.example.com", List.of());
    }

    @Test
    void setServiceAccessRule_nullGroupsTreatedAsEmpty() {
        controller.setServiceAccessRule("plex.example.com",
                new AuthzRestController.ServiceAccessRuleRequest(null));

        verify(setServiceAccessRuleUseCase).setAllowedGroups("plex.example.com", List.of());
    }

    // --- GET /access/services (list per-service access rules) ---

    @Test
    void getServiceAccessRules_returnsTheRulesMap() {
        java.util.Map<String, List<String>> rules = java.util.Map.of(
                "plex.example.com", List.of("family"), "git.example.com", List.of("devs"));
        when(getServiceAccessRulesUseCase.getServiceAccessRules()).thenReturn(rules);

        assertThat(controller.getServiceAccessRules()).isEqualTo(rules);
    }

    // --- last-admin protection surfaces as 409 Conflict ---

    @Test
    void revokeAccess_lastAdmin_mapsToConflictWithMessage() {
        ResponseEntity<ApiError> response = handler.handleLastAdmin(
                new LastAdminException("Cannot remove the last administrator — promote another admin first."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("last administrator");
    }

    @Test
    void grantRole_lastAdmin_mapsToConflictWithMessage() {
        ResponseEntity<ApiError> response = handler.handleLastAdmin(
                new LastAdminException("Cannot demote the last administrator — promote another admin first."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("last administrator");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
}
