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
