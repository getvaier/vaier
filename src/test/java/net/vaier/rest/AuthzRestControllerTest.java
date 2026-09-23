package net.vaier.rest;

import jakarta.servlet.http.HttpServletRequest;
import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.GetServiceAccessRulesUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.RecordAllowedAccessUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.SetServiceAccessRuleUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.Role;
import net.vaier.domain.ServiceCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    @Mock RecordAllowedAccessUseCase recordAllowedAccessUseCase;

    @InjectMocks AuthzRestController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "trustedProxyCidr", "172.20.0.0/16");
    }

    /** A request as Traefik makes it: from the Docker bridge, carrying the real client in the header. */
    private static HttpServletRequest requestFrom(String remoteAddress, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getRemoteAddr()).thenReturn(remoteAddress);
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }

    private static HttpServletRequest aRequest() {
        return requestFrom("172.20.0.5", "203.0.113.7");
    }

    // --- GET /authz/verify (forward-auth) ---

    @Test
    void verify_allowed_returns200WithIdentityHeaders() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).groups(List.of("family", "media")).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify(aRequest(), "friend@example.com", "plex.example.com", null, null, null, null);

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

        ResponseEntity<String> response = controller.verify(aRequest(), "friend@example.com", "plex.example.com", "Alice Smith", null, null, null);

        assertThat(response.getHeaders().getFirst("Remote-Name")).isEqualTo("Alice Smith");
    }

    @Test
    void verify_allowed_omitsRemoteNameHeaderWhenTheEntryHasNoDisplayName() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify(aRequest(), "friend@example.com", "plex.example.com", null, null, null, null);

        // Pre-approved entries have no name yet — don't emit an empty header.
        assertThat(response.getHeaders().containsKey("Remote-Name")).isFalse();
    }

    @Test
    void verify_emitsTheAuthorizationTheDecisionHandsOn_givenWhatTheClientSent() {
        AccessEntry entry = AccessEntry.builder().email("turid@example.com").role(Role.USER).build();
        ServiceCredential credential = new ServiceCredential("turid", "pw");

        record Row(AccessDecision decision, String expected) {}
        for (Row row : List.of(
                new Row(AccessDecision.allow(entry).withServiceCredential(credential), credential.authorizationHeader()),
                new Row(AccessDecision.allow(entry), "Basic typed-by-the-client"),
                // Traefik returns a non-2xx forward-auth response to the browser: never put a credential on it.
                new Row(AccessDecision.deny().withServiceCredential(credential), null))) {
            when(verifyAccessUseCase.verify("turid@example.com", "openhab.example.com", null, null, null))
                    .thenReturn(row.decision());

            ResponseEntity<String> response = controller.verify(aRequest(), "turid@example.com",
                    "openhab.example.com", null, null, null, "Basic typed-by-the-client");

            assertThat(response.getHeaders().getFirst("Authorization")).as(row.decision().toString())
                    .isEqualTo(row.expected());
        }
    }

    @Test
    void verify_denied_returns403WithBrandedHtmlPage() {
        when(verifyAccessUseCase.verify("p@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.deny());

        ResponseEntity<String> response = controller.verify(aRequest(), "p@example.com", "plex.example.com", null, null, null, null);

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

        controller.verify(aRequest(), "friend@example.com", "plex.example.com", "Alice Smith", null, null, null);

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", null, null);
    }

    @Test
    void verify_passesTheConnectorHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", null))
                .thenReturn(AccessDecision.deny());

        controller.verify(aRequest(), "friend@example.com", "plex.example.com", "Alice Smith", "github", null, null);

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", "github", null);
    }

    @Test
    void verify_passesTheConnectorUidHeaderThroughToTheUseCase() {
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", "Alice Smith", "github", "98765"))
                .thenReturn(AccessDecision.deny());

        controller.verify(aRequest(), "friend@example.com", "plex.example.com", "Alice Smith", "github", "98765", null);

        verify(verifyAccessUseCase).verify("friend@example.com", "plex.example.com", "Alice Smith", "github", "98765");
    }

    // --- GET /authz/verify records where allowed accesses come from ---

    @Test
    void verify_allowed_recordsTheAllowedAccessFromTheCallerIp() {
        AccessEntry entry = AccessEntry.builder().email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        controller.verify(requestFrom("172.20.0.5", "203.0.113.7"),
                "friend@example.com", "plex.example.com", null, null, null, null);

        verify(recordAllowedAccessUseCase)
                .recordAllowedAccess(eq("203.0.113.7"), eq("friend@example.com"), eq("plex.example.com"),
                        any(Instant.class));
    }

    /**
     * The forward-auth check is the only place in Vaier that sees which service a request was for. It used
     * to throw that away — hence "the phone reached something, somewhere" and nothing more.
     */
    @Test
    void verify_allowed_namesTheServiceThatWasReached() {
        AccessEntry entry = AccessEntry.builder().email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "grafana.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        controller.verify(requestFrom("172.20.0.5", "10.13.13.4"),
                "friend@example.com", "grafana.example.com", null, null, null, null);

        verify(recordAllowedAccessUseCase)
                .recordAllowedAccess(eq("10.13.13.4"), eq("friend@example.com"), eq("grafana.example.com"),
                        any(Instant.class));
    }

    /**
     * The one hop worth believing. A request that reached Vaier directly could put any address it liked in
     * {@code X-Forwarded-For}, so outside the trusted proxy CIDR only the socket's own address counts —
     * the decision is {@code CallerIp}'s, and this asserts the controller actually asks it.
     */
    @Test
    void verify_allowed_ignoresAForwardedForHeaderFromOutsideTheTrustedProxy() {
        AccessEntry entry = AccessEntry.builder().email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        controller.verify(requestFrom("203.0.113.99", "1.2.3.4"),
                "friend@example.com", "plex.example.com", null, null, null, null);

        verify(recordAllowedAccessUseCase)
                .recordAllowedAccess(eq("203.0.113.99"), eq("friend@example.com"), eq("plex.example.com"),
                        any(Instant.class));
    }

    /** Only an allowed access is one. A refused knock belongs to the threat side of the security view. */
    @Test
    void verify_denied_recordsNothing() {
        when(verifyAccessUseCase.verify("p@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.deny());

        controller.verify(aRequest(), "p@example.com", "plex.example.com", null, null, null, null);

        verifyNoInteractions(recordAllowedAccessUseCase);
    }

    /**
     * <b>The single most important property in this change.</b> Recording is a statistic riding on the
     * endpoint that authenticates every request to every gated service. If a broken geolocation database,
     * a full disk or anything else can make this throw, the operator is locked out of their own fleet by a
     * feature that draws dots on a map. It must fail silently or not at all.
     */
    @Test
    void verify_allowed_stillAuthenticatesWhenRecordingTheAccessBlowsUp() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).groups(List.of("family")).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));
        doThrow(new IllegalStateException("the map is on fire")).when(recordAllowedAccessUseCase)
                .recordAllowedAccess(any(), any(), any(), any());

        ResponseEntity<String> response = controller.verify(aRequest(),
                "friend@example.com", "plex.example.com", null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Remote-Email")).isEqualTo("friend@example.com");
        assertThat(response.getHeaders().getFirst("Remote-Groups")).isEqualTo("family");
    }

    /** A request Vaier cannot even read an address off must not become a 500 on the auth path. */
    @Test
    void verify_allowed_stillAuthenticatesWhenThereIsNoRequestToReadAnAddressFrom() {
        AccessEntry entry = AccessEntry.builder().email("friend@example.com").role(Role.USER).build();
        when(verifyAccessUseCase.verify("friend@example.com", "plex.example.com", null, null, null))
                .thenReturn(AccessDecision.allow(entry));

        ResponseEntity<String> response = controller.verify(null,
                "friend@example.com", "plex.example.com", null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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
        Map<String, List<String>> rules = Map.of(
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

    // --- the trusted-proxy boundary is one value, under a name that fits its scope ---

    /**
     * The forward-auth path is not the launchpad, and an operator on a different Docker bridge should not
     * have to set a {@code launchpad.*} key to fix who authenticates their fleet. One neutral key, honoured
     * by both surfaces — with the old one still read, because an operator who already set it must not be
     * broken by the rename.
     */
    @Test
    void theTrustedProxyCidrHasANeutralKeyAndStillHonoursTheOldLaunchpadOne() throws Exception {
        assertThat(resolve(Map.of())).isEqualTo("172.20.0.0/16");
        assertThat(resolve(Map.of("launchpad.trusted-proxy-cidr", "10.9.0.0/16")))
            .as("an operator who already set the launchpad key is not broken").isEqualTo("10.9.0.0/16");
        assertThat(resolve(Map.of("vaier.trusted-proxy-cidr", "10.8.0.0/16"))).isEqualTo("10.8.0.0/16");
        assertThat(resolve(Map.of("vaier.trusted-proxy-cidr", "10.8.0.0/16",
            "launchpad.trusted-proxy-cidr", "10.9.0.0/16"))).isEqualTo("10.8.0.0/16");
    }

    /** Same boundary, same expression: the two surfaces are not allowed to disagree about who Traefik is. */
    @Test
    void bothSurfacesReadTheSameTrustedProxyBoundary() throws Exception {
        assertThat(trustedProxyCidrExpression(AuthzRestController.class))
            .isEqualTo(trustedProxyCidrExpression(LaunchpadRestController.class));
    }

    private static String resolve(Map<String, String> properties) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        properties.forEach(environment::setProperty);
        return environment.resolvePlaceholders(trustedProxyCidrExpression(AuthzRestController.class));
    }

    private static String trustedProxyCidrExpression(Class<?> controller) throws Exception {
        return controller.getDeclaredField("trustedProxyCidr").getAnnotation(Value.class).value();
    }
}
