package net.vaier.application.service;

import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.Role;
import net.vaier.domain.port.ForNotifyingAdmins;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForResolvingServiceGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    ForPersistingAccessEntries forPersistingAccessEntries;

    @Mock
    ForResolvingServiceGroup forResolvingServiceGroup;

    @Mock
    ForNotifyingAdmins forNotifyingAdmins;

    @Mock
    ConfigResolver configResolver;

    @InjectMocks
    UserService service;

    // =========================================================================
    // Social-login authorization (AccessEntry domain) — verify / list / grant /
    // assign groups / revoke.
    // =========================================================================

    private static AccessEntry accessEntry(String email, Role role, List<String> groups) {
        return AccessEntry.builder().email(email).role(role).groups(groups).build();
    }

    // --- verify: unknown identity is auto-created as pending and denied ---

    @Test
    void verify_unknownEmail_autoCreatesPendingEntryAndDenies() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());

        AccessDecision decision = service.verify("newcomer@example.com", "plex.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("newcomer@example.com")
                        && e.getRole() == Role.PENDING
                        && e.getGroups().isEmpty()));
    }

    @Test
    void verify_normalisesEmailToLowercaseTrimmed() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());

        service.verify("  Newcomer@Example.com  ", "plex.example.com", null);

        verify(forPersistingAccessEntries).findByEmail("newcomer@example.com");
        verify(forPersistingAccessEntries).upsert(argThat(e -> e.getEmail().equals("newcomer@example.com")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void verify_blankEmail_deniesWithoutTouchingTheStore(String email) {
        AccessDecision decision = service.verify(email, "plex.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
        verifyNoInteractions(forPersistingAccessEntries);
    }

    // --- verify: a brand-new identity notifies admins exactly once ---

    @Test
    void verify_unknownEmail_notifiesAdminsOfNewPendingIdentityExactlyOnce() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());

        service.verify("newcomer@example.com", "plex.example.com", null);

        verify(forNotifyingAdmins, times(1)).notifyNewPendingIdentity("newcomer@example.com");
    }

    @Test
    void verify_unknownEmail_notifiesWithTheNormalisedEmail() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());

        service.verify("  Newcomer@Example.com  ", "plex.example.com", null);

        verify(forNotifyingAdmins).notifyNewPendingIdentity("newcomer@example.com");
    }

    @Test
    void verify_knownPendingEmail_doesNotNotify() {
        when(forPersistingAccessEntries.findByEmail("p@example.com"))
                .thenReturn(Optional.of(accessEntry("p@example.com", Role.PENDING, List.of())));

        service.verify("p@example.com", "plex.example.com", null);

        verify(forNotifyingAdmins, never()).notifyNewPendingIdentity(any());
    }

    @Test
    void verify_allowedDecision_doesNotNotify() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of("family"))));
        when(forResolvingServiceGroup.requiredGroupForHost("plex.example.com")).thenReturn(Optional.of("family"));

        service.verify("friend@example.com", "plex.example.com", null);

        verify(forNotifyingAdmins, never()).notifyNewPendingIdentity(any());
    }

    @Test
    void verify_stillDeniesNewIdentityEvenWhenNotifierThrows() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("notifier blew up"))
                .when(forNotifyingAdmins).notifyNewPendingIdentity(any());

        AccessDecision decision = service.verify("newcomer@example.com", "plex.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
        verify(forPersistingAccessEntries).upsert(any());
    }

    // --- verify: known pending is denied ---

    @Test
    void verify_knownPendingEmail_denies() {
        when(forPersistingAccessEntries.findByEmail("p@example.com"))
                .thenReturn(Optional.of(accessEntry("p@example.com", Role.PENDING, List.of())));

        AccessDecision decision = service.verify("p@example.com", "plex.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
        verify(forPersistingAccessEntries, never()).upsert(any());
    }

    // --- verify: service hosts resolve the required group ---

    @Test
    void verify_userInRequiredGroup_allowsAndEmitsIdentityHeaders() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of("family"))));
        when(forResolvingServiceGroup.requiredGroupForHost("plex.example.com")).thenReturn(Optional.of("family"));

        AccessDecision decision = service.verify("friend@example.com", "plex.example.com", null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getEmail()).isEqualTo("friend@example.com");
        assertThat(decision.getGroups()).containsExactly("family");
    }

    @Test
    void verify_userNotInRequiredGroup_denies() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of("media"))));
        when(forResolvingServiceGroup.requiredGroupForHost("plex.example.com")).thenReturn(Optional.of("family"));

        AccessDecision decision = service.verify("friend@example.com", "plex.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
    }

    @Test
    void verify_serviceHostWithoutRequiredGroup_allowsAnyApprovedUser() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of())));
        when(forResolvingServiceGroup.requiredGroupForHost("open.example.com")).thenReturn(Optional.empty());

        AccessDecision decision = service.verify("friend@example.com", "open.example.com", null);

        assertThat(decision.isAllowed()).isTrue();
    }

    // --- verify: the Vaier console host requires admin ---

    @Test
    void verify_adminReachingConsoleHost_allows() {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(forPersistingAccessEntries.findByEmail("boss@example.com"))
                .thenReturn(Optional.of(accessEntry("boss@example.com", Role.ADMIN, List.of("admins"))));

        AccessDecision decision = service.verify("boss@example.com", "vaier.example.com", null);

        assertThat(decision.isAllowed()).isTrue();
        // The console host is decided by the admin-only predicate, not by the service-group port.
        verifyNoInteractions(forResolvingServiceGroup);
    }

    @Test
    void verify_ordinaryUserReachingConsoleHost_denies() {
        when(configResolver.getDomain()).thenReturn("example.com");
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of("admins"))));

        AccessDecision decision = service.verify("friend@example.com", "vaier.example.com", null);

        assertThat(decision.isAllowed()).isFalse();
    }

    // --- verify: display-name capture ---

    @Test
    void verify_unknownEmail_capturesDisplayNameFromHeaderOnThePendingEntry() {
        when(forPersistingAccessEntries.findByEmail("newcomer@example.com")).thenReturn(Optional.empty());

        service.verify("newcomer@example.com", "plex.example.com", "  Alice Smith  ");

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("newcomer@example.com")
                        && e.getRole() == Role.PENDING
                        && "Alice Smith".equals(e.getName())));
    }

    @Test
    void verify_knownEmail_refreshesDisplayNameFromHeader() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of())));
        when(forResolvingServiceGroup.requiredGroupForHost("open.example.com")).thenReturn(Optional.empty());

        service.verify("friend@example.com", "open.example.com", "Alice Smith");

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("friend@example.com") && "Alice Smith".equals(e.getName())));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void verify_knownEmailWithName_isNotWipedWhenHeaderAbsent(String header) {
        when(forPersistingAccessEntries.findByEmail("friend@example.com")).thenReturn(Optional.of(
                AccessEntry.builder().email("friend@example.com").role(Role.USER).groups(List.of()).name("Alice").build()));
        when(forResolvingServiceGroup.requiredGroupForHost("open.example.com")).thenReturn(Optional.empty());

        AccessDecision decision = service.verify("friend@example.com", "open.example.com", header);

        // The name is unchanged, so no wiping write happens.
        assertThat(decision.isAllowed()).isTrue();
        verify(forPersistingAccessEntries, never()).upsert(any());
    }

    @Test
    void grantRole_preservesDisplayName() {
        when(forPersistingAccessEntries.findByEmail("p@example.com")).thenReturn(Optional.of(
                AccessEntry.builder().email("p@example.com").role(Role.PENDING).groups(List.of("family")).name("Alice").build()));

        service.grantRole("p@example.com", Role.USER);

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getRole() == Role.USER && "Alice".equals(e.getName())));
    }

    @Test
    void assignGroups_preservesDisplayName() {
        when(forPersistingAccessEntries.findByEmail("u@example.com")).thenReturn(Optional.of(
                AccessEntry.builder().email("u@example.com").role(Role.USER).groups(List.of()).name("Alice").build()));

        service.assignGroups("u@example.com", List.of("family"));

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getGroups().equals(List.of("family")) && "Alice".equals(e.getName())));
    }

    // --- listAccessEntries ---

    @Test
    void listAccessEntries_delegatesToPort() {
        AccessEntry e = accessEntry("a@example.com", Role.USER, List.of());
        when(forPersistingAccessEntries.getEntries()).thenReturn(List.of(e));

        assertThat(service.listAccessEntries()).containsExactly(e);
    }

    // --- grantRole ---

    @Test
    void grantRole_existingEntry_setsRoleAndPreservesGroups() {
        when(forPersistingAccessEntries.findByEmail("p@example.com"))
                .thenReturn(Optional.of(accessEntry("p@example.com", Role.PENDING, List.of("family"))));

        service.grantRole("p@example.com", Role.USER);

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("p@example.com")
                        && e.getRole() == Role.USER
                        && e.getGroups().equals(List.of("family"))));
    }

    @Test
    void grantRole_unknownEmail_createsEntryWithEmptyGroups() {
        when(forPersistingAccessEntries.findByEmail("new@example.com")).thenReturn(Optional.empty());

        service.grantRole("new@example.com", Role.ADMIN);

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("new@example.com")
                        && e.getRole() == Role.ADMIN
                        && e.getGroups().isEmpty()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void grantRole_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.grantRole(email, Role.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingAccessEntries);
    }

    @Test
    void grantRole_rejectsNullRole() {
        assertThatThrownBy(() -> service.grantRole("a@example.com", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");

        verifyNoInteractions(forPersistingAccessEntries);
    }

    // --- assignGroups ---

    @Test
    void assignGroups_existingEntry_setsGroupsAndPreservesRole() {
        when(forPersistingAccessEntries.findByEmail("u@example.com"))
                .thenReturn(Optional.of(accessEntry("u@example.com", Role.USER, List.of("old"))));

        service.assignGroups("u@example.com", List.of("family", "media"));

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("u@example.com")
                        && e.getRole() == Role.USER
                        && e.getGroups().equals(List.of("family", "media"))));
    }

    @Test
    void assignGroups_filtersBlankAndDuplicates() {
        when(forPersistingAccessEntries.findByEmail("u@example.com"))
                .thenReturn(Optional.of(accessEntry("u@example.com", Role.USER, List.of())));

        service.assignGroups("u@example.com", Arrays.asList("family", "", null, "family", "media"));

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getGroups().equals(List.of("family", "media"))));
    }

    @Test
    void assignGroups_unknownEmail_defaultsToPendingRole() {
        when(forPersistingAccessEntries.findByEmail("new@example.com")).thenReturn(Optional.empty());

        service.assignGroups("new@example.com", List.of("family"));

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getRole() == Role.PENDING && e.getGroups().equals(List.of("family"))));
    }

    @Test
    void assignGroups_treatsNullGroupsAsEmpty() {
        when(forPersistingAccessEntries.findByEmail("u@example.com"))
                .thenReturn(Optional.of(accessEntry("u@example.com", Role.USER, List.of("old"))));

        service.assignGroups("u@example.com", null);

        verify(forPersistingAccessEntries).upsert(argThat(e -> e.getGroups().isEmpty()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void assignGroups_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.assignGroups(email, List.of("family")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingAccessEntries);
    }

    // --- revokeAccess ---

    @Test
    void revokeAccess_deletesEntry() {
        service.revokeAccess("gone@example.com");

        verify(forPersistingAccessEntries).delete("gone@example.com");
    }

    @Test
    void revokeAccess_normalisesEmail() {
        service.revokeAccess("  Gone@Example.com ");

        verify(forPersistingAccessEntries).delete("gone@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void revokeAccess_rejectsBlankEmail(String email) {
        assertThatThrownBy(() -> service.revokeAccess(email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        verifyNoInteractions(forPersistingAccessEntries);
    }

    // --- last-admin protection: the store must always retain at least one admin ---

    @Test
    void revokeAccess_refusesToRemoveTheLastAdmin() {
        when(forPersistingAccessEntries.getEntries()).thenReturn(List.of(
                accessEntry("boss@example.com", Role.ADMIN, List.of()),
                accessEntry("friend@example.com", Role.USER, List.of())));

        assertThatThrownBy(() -> service.revokeAccess("boss@example.com"))
                .isInstanceOf(LastAdminException.class)
                .hasMessageContaining("last administrator");

        verify(forPersistingAccessEntries, never()).delete(any());
    }

    @Test
    void revokeAccess_allowsRemovingAnAdminWhenAnotherAdminRemains() {
        when(forPersistingAccessEntries.getEntries()).thenReturn(List.of(
                accessEntry("boss@example.com", Role.ADMIN, List.of()),
                accessEntry("second@example.com", Role.ADMIN, List.of())));

        service.revokeAccess("boss@example.com");

        verify(forPersistingAccessEntries).delete("boss@example.com");
    }

    @Test
    void grantRole_refusesToDemoteTheLastAdmin() {
        when(forPersistingAccessEntries.getEntries()).thenReturn(List.of(
                accessEntry("boss@example.com", Role.ADMIN, List.of()),
                accessEntry("friend@example.com", Role.USER, List.of())));

        assertThatThrownBy(() -> service.grantRole("boss@example.com", Role.USER))
                .isInstanceOf(LastAdminException.class)
                .hasMessageContaining("last administrator");

        verify(forPersistingAccessEntries, never()).upsert(any());
    }

    @Test
    void grantRole_allowsDemotingAnAdminWhenAnotherAdminRemains() {
        when(forPersistingAccessEntries.getEntries()).thenReturn(List.of(
                accessEntry("boss@example.com", Role.ADMIN, List.of()),
                accessEntry("second@example.com", Role.ADMIN, List.of())));
        when(forPersistingAccessEntries.findByEmail("boss@example.com"))
                .thenReturn(Optional.of(accessEntry("boss@example.com", Role.ADMIN, List.of())));

        service.grantRole("boss@example.com", Role.USER);

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("boss@example.com") && e.getRole() == Role.USER));
    }

    @Test
    void grantRole_grantingAdminNeverTripsTheLastAdminGuard() {
        when(forPersistingAccessEntries.findByEmail("friend@example.com"))
                .thenReturn(Optional.of(accessEntry("friend@example.com", Role.USER, List.of())));

        service.grantRole("friend@example.com", Role.ADMIN);

        verify(forPersistingAccessEntries).upsert(argThat(e ->
                e.getEmail().equals("friend@example.com") && e.getRole() == Role.ADMIN));
    }
}
