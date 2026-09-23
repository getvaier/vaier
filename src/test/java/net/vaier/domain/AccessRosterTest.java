package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "at least one admin" decision lives here, in the domain. {@link AccessRoster} answers how many
 * admins the access store holds and whether a given identity is the sole admin — the rule that keeps
 * the console from ever being locked out for everyone once Authelia is gone.
 */
class AccessRosterTest {

    private static AccessEntry entry(String email, Role role) {
        return AccessEntry.builder().email(email).role(role).groups(List.of()).build();
    }

    // --- claimsFirstAdmin: the first-run claim (#264) ---

    @Test
    void claimsFirstAdmin_onlyForASignInThroughTheLocalConnector_whileNoAdminExists() {
        record Row(List<AccessEntry> entries, String provider, boolean claims) {}
        List<AccessEntry> nobody = List.of();
        List<AccessEntry> someAdmin = List.of(entry("boss@example.com", Role.ADMIN));
        List<AccessEntry> onlyPending = List.of(entry("new@example.com", Role.PENDING));
        for (Row row : List.of(
                new Row(nobody, "local", true),
                new Row(nobody, " Local ", true),
                new Row(onlyPending, "local", true),      // pending entries are not admins; the store is still unclaimed
                new Row(nobody, "google", false),         // a provider sign-in never claims: that is the pending path
                new Row(nobody, null, false),
                new Row(someAdmin, "local", false))) {    // once an admin exists the door is closed, whoever knocks
            assertThat(new AccessRoster(row.entries()).claimsFirstAdmin(row.provider()))
                .as("%s via %s", row.entries(), row.provider()).isEqualTo(row.claims());
        }
    }

    // --- needsConfiguredAdmin: who can still reach the console (#264 handover) ---

    @Test
    void needsConfiguredAdmin_whenNoAdminCanStillSignIn() {
        record Row(List<AccessEntry> entries, boolean providerConfigured, boolean needs, boolean lockedOut) {}
        AccessEntry firstRunAdmin = entry("admin@example.com", Role.ADMIN).toBuilder().provider("local").build();
        AccessEntry googleAdmin = entry("you@gmail.com", Role.ADMIN).toBuilder().provider("google").build();
        AccessEntry seededAdmin = entry("owner@gmail.com", Role.ADMIN);
        for (Row row : List.of(
                new Row(List.of(), false, true, false),                      // never had an admin: expected, not locked out
                new Row(List.of(), true, true, false),
                new Row(List.of(firstRunAdmin), false, false, false),        // the first-run door is still open
                new Row(List.of(firstRunAdmin), true, true, true),           // a provider closed it: locked out
                new Row(List.of(firstRunAdmin, googleAdmin), true, false, false),
                new Row(List.of(firstRunAdmin, seededAdmin), true, false, false))) { // seeded, never signed in: may yet
            AccessRoster roster = new AccessRoster(row.entries());
            String label = row.entries() + ", provider configured: " + row.providerConfigured();
            assertThat(roster.needsConfiguredAdmin(row.providerConfigured())).as(label).isEqualTo(row.needs());
            assertThat(roster.isLockedOut(row.providerConfigured())).as(label).isEqualTo(row.lockedOut());
        }
    }

    // --- adminCount ---

    @Test
    void adminCount_zeroForEmptyStore() {
        assertThat(new AccessRoster(List.of()).adminCount()).isZero();
    }

    @Test
    void adminCount_zeroWhenNullEntries() {
        assertThat(new AccessRoster(null).adminCount()).isZero();
    }

    @Test
    void adminCount_oneForASingleAdmin() {
        assertThat(new AccessRoster(List.of(
                entry("boss@example.com", Role.ADMIN),
                entry("friend@example.com", Role.USER),
                entry("new@example.com", Role.PENDING)
        )).adminCount()).isEqualTo(1);
    }

    @Test
    void adminCount_countsEveryAdmin() {
        assertThat(new AccessRoster(List.of(
                entry("a@example.com", Role.ADMIN),
                entry("b@example.com", Role.ADMIN),
                entry("c@example.com", Role.USER)
        )).adminCount()).isEqualTo(2);
    }

    // --- isOnlyAdmin ---

    @Test
    void isOnlyAdmin_trueWhenTargetIsTheSoleAdmin() {
        AccessRoster roster = new AccessRoster(List.of(
                entry("boss@example.com", Role.ADMIN),
                entry("friend@example.com", Role.USER)));

        assertThat(roster.isOnlyAdmin("boss@example.com")).isTrue();
    }

    @Test
    void isOnlyAdmin_falseWhenAnotherAdminRemains() {
        AccessRoster roster = new AccessRoster(List.of(
                entry("a@example.com", Role.ADMIN),
                entry("b@example.com", Role.ADMIN)));

        assertThat(roster.isOnlyAdmin("a@example.com")).isFalse();
        assertThat(roster.isOnlyAdmin("b@example.com")).isFalse();
    }

    @Test
    void isOnlyAdmin_falseWhenTargetIsNotAnAdmin() {
        AccessRoster roster = new AccessRoster(List.of(
                entry("boss@example.com", Role.ADMIN),
                entry("friend@example.com", Role.USER)));

        // friend is not an admin — demoting or revoking them never touches the last-admin invariant.
        assertThat(roster.isOnlyAdmin("friend@example.com")).isFalse();
    }

    @Test
    void isOnlyAdmin_falseForAnEmailNotInTheStore() {
        AccessRoster roster = new AccessRoster(List.of(entry("boss@example.com", Role.ADMIN)));

        assertThat(roster.isOnlyAdmin("stranger@example.com")).isFalse();
    }

    @Test
    void isOnlyAdmin_falseForEmptyStore() {
        assertThat(new AccessRoster(List.of()).isOnlyAdmin("boss@example.com")).isFalse();
    }

    @Test
    void isOnlyAdmin_falseForNullEmail() {
        assertThat(new AccessRoster(List.of(entry("boss@example.com", Role.ADMIN))).isOnlyAdmin(null)).isFalse();
    }

    @Test
    void isOnlyAdmin_matchesEmailCaseInsensitively() {
        AccessRoster roster = new AccessRoster(List.of(entry("boss@example.com", Role.ADMIN)));

        assertThat(roster.isOnlyAdmin("BOSS@Example.com")).isTrue();
    }
}
