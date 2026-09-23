package net.vaier.domain;

import net.vaier.domain.OwnSignIn.Kind;
import net.vaier.domain.port.ForPersistingOpenServiceState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenServiceTrackerTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    /** The store outlives the tracker, as the file outlives a restart. */
    private static final class Store implements ForPersistingOpenServiceState {
        OpenServiceState state = OpenServiceState.empty();

        @Override
        public OpenServiceState read() {
            return state;
        }

        @Override
        public void save(OpenServiceState state) {
            this.state = state;
        }
    }

    private static ReverseProxyRoute route(String name, AuthMode mode) {
        return ReverseProxyRoute.builder().name(name).domainName(name + ".example.com").address("192.168.1.40")
            .port(8080).middlewares(mode.authMiddlewareNames()).build();
    }

    private static Map<String, OwnSignIn> seen(String name, Kind kind) {
        return Map.of(name, new OwnSignIn(kind, null, null, NOW));
    }

    private static List<String> alerted(OpenServiceTracker tracker, List<ReverseProxyRoute> routes,
                                        Map<String, OwnSignIn> signIns) {
        return tracker.observe(routes, signIns).stream().map(OpenService::routeName).toList();
    }

    @Test
    void anOpenServiceIsMailedOnce_evenAcrossARestart_andAnUnknownLookKeepsThatSilence() {
        Store store = new Store();
        List<ReverseProxyRoute> rack = List.of(route("rack", AuthMode.NONE));

        assertThat(alerted(new OpenServiceTracker(store), rack, seen("rack", Kind.NONE))).containsExactly("rack");
        assertThat(alerted(new OpenServiceTracker(store), rack, seen("rack", Kind.NONE))).isEmpty();
        // Unknown is not no: a backend that is down for a round has not been fixed.
        assertThat(alerted(new OpenServiceTracker(store), rack, seen("rack", Kind.UNKNOWN))).isEmpty();
        assertThat(alerted(new OpenServiceTracker(store), rack, Map.of())).isEmpty();
        assertThat(alerted(new OpenServiceTracker(store), rack, seen("rack", Kind.NONE))).isEmpty();
    }

    @Test
    void aFixedService_clearsTheLatchSilently_soOpeningItAgainIsNewsAgain() {
        record Row(String why, List<ReverseProxyRoute> routes, Map<String, OwnSignIn> signIns) {}
        for (Row fixed : List.of(
                new Row("Vaier's sign-in put in front", List.of(route("rack", AuthMode.SOCIAL)), seen("rack", Kind.NONE)),
                new Row("its own sign-in turned on", List.of(route("rack", AuthMode.NONE)), seen("rack", Kind.SIGN_IN_PAGE)),
                new Row("unpublished", List.of(), Map.of()))) {
            Store store = new Store();
            OpenServiceTracker tracker = new OpenServiceTracker(store);
            List<ReverseProxyRoute> open = List.of(route("rack", AuthMode.NONE));
            tracker.observe(open, seen("rack", Kind.NONE));

            assertThat(alerted(tracker, fixed.routes(), fixed.signIns())).as(fixed.why()).isEmpty();
            assertThat(alerted(tracker, open, seen("rack", Kind.NONE))).as(fixed.why()).containsExactly("rack");
        }
    }

    @Test
    void onlyNoSignInOfItsOwnBehindNoSignInOfVaiersIsOpen() {
        for (Kind kind : List.of(Kind.BASIC, Kind.OTHER_CHALLENGE, Kind.SIGN_IN_PAGE, Kind.UNKNOWN)) {
            assertThat(alerted(new OpenServiceTracker(new Store()), List.of(route("pihole", AuthMode.NONE)),
                seen("pihole", kind))).as(kind.name()).isEmpty();
        }
        ReverseProxyRoute stream = route("mqtt", AuthMode.NONE).toBuilder().stream(true).build();
        assertThat(alerted(new OpenServiceTracker(new Store()), List.of(stream), seen("mqtt", Kind.NONE))).isEmpty();
    }

    @Test
    void aServiceMeantToBePublicIsNeverMailed_untilThatIsTakenBack_andForgottenWithItsRoute() {
        Store store = new Store();
        OpenServiceTracker tracker = new OpenServiceTracker(store);
        List<ReverseProxyRoute> rack = List.of(route("rack", AuthMode.NONE));

        tracker.meantToBePublic("rack", true);
        assertThat(alerted(tracker, rack, seen("rack", Kind.NONE))).isEmpty();
        assertThat(store.state.isMeantToBePublic("rack")).isTrue();

        tracker.meantToBePublic("rack", false);
        assertThat(alerted(tracker, rack, seen("rack", Kind.NONE))).containsExactly("rack");

        tracker.meantToBePublic("rack", true);
        tracker.observe(List.of(), Map.of());
        assertThat(store.state.isMeantToBePublic("rack")).as("unpublished: nothing to remember").isFalse();
    }
}
