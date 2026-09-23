package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.vaier.domain.IdentityProvider.GITHUB;
import static net.vaier.domain.IdentityProvider.GOOGLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The providers added from Settings, and the first-run door they must not slam shut before an admin has
 * walked through a provider. dex-init and oauth2-proxy-init apply the same door rule in shell.
 */
class SignInSettingsTest {

    private static final ProviderCredentials CREDS = new ProviderCredentials("id", "secret");
    private static final SignInSettings GOOGLE_DOOR_OPEN = new SignInSettings(Map.of(GOOGLE, CREDS), true);
    private static final SignInSettings GOOGLE_DOOR_CLOSED = new SignInSettings(Map.of(GOOGLE, CREDS), false);

    @Test
    void firstRunDoor_isOpenWhileNoProviderExistsAnywhere_orWhileSettingsHoldItOpen() {
        record Row(String label, SignInSettings settings, Set<IdentityProvider> env, boolean open) {}
        for (Row row : List.of(
                new Row("fresh install", SignInSettings.none(), Set.of(), true),
                new Row("provider in .env, as before this slice", SignInSettings.none(), Set.of(GITHUB), false),
                new Row("added from Settings, no admin through it yet", GOOGLE_DOOR_OPEN, Set.of(), true),
                new Row("an admin came through it", GOOGLE_DOOR_CLOSED, Set.of(), false))) {
            assertThat(row.settings().isFirstRunDoorOpen(row.env())).as(row.label()).isEqualTo(row.open());
        }
    }

    @Test
    void withProvider_leavesTheDoorAsItStood_andRefusesAProviderDotEnvAlreadySets() {
        assertThat(SignInSettings.none().withProvider(GOOGLE, CREDS, Set.of()))
            .as("the first provider keeps the door open until an admin signs in with it")
            .isEqualTo(GOOGLE_DOOR_OPEN);
        assertThat(SignInSettings.none().withProvider(GOOGLE, CREDS, Set.of(GITHUB)))
            .as("a door .env already closed stays closed")
            .isEqualTo(GOOGLE_DOOR_CLOSED);
        assertThat(GOOGLE_DOOR_CLOSED.withProvider(GOOGLE, new ProviderCredentials("new-id", "new-secret"), Set.of())
                .providers().get(GOOGLE).clientId())
            .as("saving again replaces the credentials").isEqualTo("new-id");

        assertThatThrownBy(() -> SignInSettings.none().withProvider(GITHUB, CREDS, Set.of(GITHUB)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(".env");
    }

    @Test
    void closesFirstRunDoorOn_onlyAnAdminArrivingThroughAProvider_whileTheDoorIsOpen() {
        record Row(String label, SignInSettings settings, Role role, String provider, boolean closes) {}
        for (Row row : List.of(
                new Row("admin through Google", GOOGLE_DOOR_OPEN, Role.ADMIN, "google", true),
                new Row("admin through GitHub", GOOGLE_DOOR_OPEN, Role.ADMIN, "github", true),
                new Row("admin still on the first-run password", GOOGLE_DOOR_OPEN, Role.ADMIN, "local", false),
                new Row("a user through Google", GOOGLE_DOOR_OPEN, Role.USER, "google", false),
                new Row("door already closed", GOOGLE_DOOR_CLOSED, Role.ADMIN, "google", false),
                new Row("no settings at all", SignInSettings.none(), Role.ADMIN, "google", false))) {
            AccessEntry entry = AccessEntry.builder().email("you@example.com").role(row.role())
                .groups(List.of()).provider(row.provider()).build();
            assertThat(row.settings().closesFirstRunDoorOn(entry)).as(row.label()).isEqualTo(row.closes());
        }
        assertThat(GOOGLE_DOOR_OPEN.withFirstRunDoorClosed()).isEqualTo(GOOGLE_DOOR_CLOSED);
    }

    @Test
    void standings_sayWhereEachProviderComesFrom_withDotEnvWinning() {
        SignInSettings settings = new SignInSettings(Map.of(GOOGLE, CREDS, GITHUB, CREDS), true);

        assertThat(settings.standings(Map.of(GITHUB, "env-id"))).containsExactly(
            new ProviderStanding(GOOGLE, ProviderSource.SETTINGS, "id"),
            new ProviderStanding(GITHUB, ProviderSource.ENVIRONMENT, "env-id"));
        assertThat(SignInSettings.none().standings(Map.of())).containsExactly(
            new ProviderStanding(GOOGLE, ProviderSource.NOT_CONFIGURED, null),
            new ProviderStanding(GITHUB, ProviderSource.NOT_CONFIGURED, null));
    }
}
