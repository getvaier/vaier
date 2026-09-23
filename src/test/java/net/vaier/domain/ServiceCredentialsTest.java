package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceCredentialsTest {

    private static final String OPENHAB = "openhab.example.com";
    private static final ServiceCredential SHARED = new ServiceCredential("house", "shared-pw");
    private static final ServiceCredential TURIDS = new ServiceCredential("turid", "turids-pw");

    private static ReverseProxyRoute route(String host, AuthMode mode) {
        return ReverseProxyRoute.builder().name(host.replace('.', '-') + "-router").domainName(host)
            .address("10.13.13.5").port(8080).middlewares(mode.authMiddlewareNames()).build();
    }

    private static final List<ReverseProxyRoute> ROUTES = List.of(route(OPENHAB, AuthMode.SOCIAL));
    private static final List<AccessEntry> PEOPLE = List.of(
        AccessEntry.builder().email("turid@example.com").role(Role.USER).build());

    private static ServiceCredentials openhabWithBoth() {
        return ServiceCredentials.empty()
            .withShared(OPENHAB, SHARED, ROUTES)
            .withPersonal(OPENHAB, "turid@example.com", TURIDS, ROUTES, PEOPLE);
    }

    @Test
    void aPersonalCredentialWinsOverTheSharedOne_whichEveryoneElseGets() {
        ServiceCredentials credentials = openhabWithBoth();

        assertThat(credentials.credentialFor(OPENHAB, "turid@example.com")).contains(TURIDS);
        assertThat(credentials.credentialFor("OpenHAB.example.com", " Turid@Example.com "))
            .as("host and email are matched case-insensitively").contains(TURIDS);
        assertThat(credentials.credentialFor(OPENHAB, "geir@example.com")).contains(SHARED);
        assertThat(credentials.credentialFor("plex.example.com", "turid@example.com")).isEmpty();
        assertThat(credentials.withoutShared(OPENHAB).credentialFor(OPENHAB, "geir@example.com"))
            .as("neither: nothing is handed on").isEmpty();
    }

    @Test
    void onlyAServiceBehindSocialLoginCanCarryOne_becauseNothingElseRunsVaiersCheck() {
        record Row(String why, List<ReverseProxyRoute> routes) {}
        for (Row row : List.of(
                new Row("public", List.of(route(OPENHAB, AuthMode.NONE))),
                new Row("not published", List.of()),
                new Row("a label route Vaier does not manage (the console)",
                    List.of(route(OPENHAB, AuthMode.SOCIAL).toBuilder().name("vaier@docker").build())))) {
            assertThatThrownBy(() -> ServiceCredentials.empty().withShared(OPENHAB, SHARED, row.routes()))
                .as(row.why()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("social login");
            assertThatThrownBy(() -> ServiceCredentials.empty()
                    .withPersonal(OPENHAB, "turid@example.com", TURIDS, row.routes(), PEOPLE))
                .as(row.why()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("social login");
        }
        List<ReverseProxyRoute> mixed = List.of(route(OPENHAB, AuthMode.NONE),
            route(OPENHAB, AuthMode.SOCIAL).toBuilder().name("openhab-api-router").pathPrefix("/api").build());
        assertThat(ServiceCredentials.empty().withShared(OPENHAB, SHARED, mixed).credentialFor(OPENHAB, "a@b.c"))
            .as("one social route on the host is enough").contains(SHARED);
    }

    @Test
    void aPersonalCredentialNamesSomeoneWithAnAccessEntry() {
        assertThatThrownBy(() -> ServiceCredentials.empty()
                .withPersonal(OPENHAB, "stranger@example.com", TURIDS, ROUTES, PEOPLE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stranger@example.com");
    }

    @Test
    void forgetting_dropsOnlyWhatWasNamed_andAServiceLeftWithNothingDisappears() {
        ServiceCredentials credentials = openhabWithBoth();

        assertThat(credentials.withoutPersonal(OPENHAB, "Turid@example.com").credentialFor(OPENHAB, "turid@example.com"))
            .contains(SHARED);
        assertThat(credentials.withoutPerson("turid@example.com").credentialFor(OPENHAB, "turid@example.com"))
            .as("revoking a person forgets theirs everywhere").contains(SHARED);
        assertThat(credentials.withoutShared(OPENHAB).withoutPersonal(OPENHAB, "turid@example.com").getByService())
            .isEmpty();
    }

    @Test
    void unpublishing_forgetsAServiceOnlyOnceNoRouteIsLeftOnItsHost() {
        ServiceCredentials credentials = openhabWithBoth();
        ReverseProxyRoute sibling = route(OPENHAB, AuthMode.SOCIAL).toBuilder()
            .name("openhab-api-router").pathPrefix("/api").build();
        ReverseProxyRoute leftoverSignInRouter = route(OPENHAB, AuthMode.NONE).toBuilder()
            .name(ReverseProxyRoute.oauth2EndpointsRouterName(OPENHAB)).build();

        assertThat(credentials.afterUnpublishing(OPENHAB, List.of(sibling)).getByService()).containsKey(OPENHAB);
        assertThat(credentials.afterUnpublishing(OPENHAB, List.of(leftoverSignInRouter)).getByService()).isEmpty();
        assertThat(credentials.afterUnpublishing("plex.example.com", List.of()).getByService())
            .as("another host's unpublish leaves this one alone").containsKey(OPENHAB);
    }
}
