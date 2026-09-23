package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessDecisionTest {

    @Test
    void deny_isNotAllowedAndCarriesNoIdentity() {
        AccessDecision decision = AccessDecision.deny();

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getEmail()).isNull();
        assertThat(decision.getUser()).isNull();
        assertThat(decision.getName()).isNull();
        assertThat(decision.getGroups()).isEmpty();
    }

    @Test
    void allow_carriesTheIdentityHeadersFromTheEntry() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).groups(List.of("family", "media")).build();

        AccessDecision decision = AccessDecision.allow(entry);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getEmail()).isEqualTo("friend@example.com");
        assertThat(decision.getUser()).isEqualTo("friend@example.com");
        assertThat(decision.getGroups()).containsExactly("family", "media");
    }

    @Test
    void allow_carriesTheDisplayNameFromTheEntry() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).name("Alice Smith").build();

        AccessDecision decision = AccessDecision.allow(entry);

        assertThat(decision.getName()).isEqualTo("Alice Smith");
    }

    @Test
    void allow_nameIsNullWhenEntryHasNone() {
        AccessEntry entry = AccessEntry.builder()
                .email("friend@example.com").role(Role.USER).build();

        AccessDecision decision = AccessDecision.allow(entry);

        assertThat(decision.getName()).isNull();
    }

    @Test
    void authorizationFor_handsOnTheServiceCredential_elseWhatTheClientSent_andNothingOnADenial() {
        AccessEntry entry = AccessEntry.builder().email("turid@example.com").role(Role.USER).build();
        ServiceCredential credential = new ServiceCredential("turid", "pw");
        AccessDecision allowed = AccessDecision.allow(entry);

        assertThat(allowed.withServiceCredential(credential).authorizationFor("Basic typed"))
            .contains(credential.authorizationHeader());
        // Traefik strips a listed response header from the request whether or not the check returned one, so
        // passing the client's own back is what keeps it reaching the service.
        assertThat(allowed.authorizationFor("Basic typed")).contains("Basic typed");
        assertThat(allowed.authorizationFor(null)).isEmpty();
        assertThat(AccessDecision.deny().withServiceCredential(credential).authorizationFor("Basic typed"))
            .as("a denial goes back to the browser, so it carries no credential").isEmpty();
    }

    @Test
    void groupsHeader_joinsGroupsWithCommas() {
        AccessEntry entry = AccessEntry.builder()
                .email("a@example.com").role(Role.USER).groups(List.of("family", "media")).build();

        assertThat(AccessDecision.allow(entry).groupsHeader()).isEqualTo("family,media");
    }

    @Test
    void groupsHeader_isEmptyStringWhenNoGroups() {
        AccessEntry entry = AccessEntry.builder()
                .email("a@example.com").role(Role.ADMIN).groups(null).build();

        AccessDecision decision = AccessDecision.allow(entry);

        assertThat(decision.getGroups()).isEmpty();
        assertThat(decision.groupsHeader()).isEmpty();
    }
}
