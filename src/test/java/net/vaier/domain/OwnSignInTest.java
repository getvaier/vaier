package net.vaier.domain;

import net.vaier.domain.OwnSignIn.Advice;
import net.vaier.domain.OwnSignIn.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OwnSignInTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final String HTML = "text/html; charset=utf-8";

    private static Optional<ServiceProbeAnswer> answer(int status, String challenge, String location,
                                                       String contentType, String body) {
        return Optional.of(new ServiceProbeAnswer(status, challenge, location, contentType, body));
    }

    @Test
    void classify_readsWhatTheServiceAsksFor_andNeverCallsAnUncertainAnswerNone() {
        String realContent = "<html><body><h1>Rack</h1><table><tr><td>Server one</td><td>42 °C</td></tr>"
            + "<tr><td>Server two</td><td>39 °C</td></tr></table><p>Fans at 1200 rpm, all good.</p></body></html>";
        record Row(String why, Optional<ServiceProbeAnswer> answer, Kind kind, String detail, String app) {}
        for (Row row : List.of(
                new Row("basic auth", answer(401, "Basic realm=\"openHAB\"", null, HTML, ""),
                    Kind.BASIC, "openHAB", null),
                new Row("basic auth, no realm", answer(401, "basic", null, HTML, ""), Kind.BASIC, null, null),
                new Row("a challenge Vaier cannot answer", answer(401, "Bearer realm=\"api\"", null, null, ""),
                    Kind.OTHER_CHALLENGE, "Bearer", null),
                new Row("digest", answer(401, "Digest realm=\"x\", nonce=\"y\"", null, null, ""),
                    Kind.OTHER_CHALLENGE, "Digest", null),
                new Row("401 with no challenge", answer(401, null, null, HTML, ""), Kind.UNKNOWN, null, null),
                new Row("redirect to a sign-in path", answer(302, null, "/accounts/login/?next=/", HTML, ""),
                    Kind.SIGN_IN_PAGE, "/accounts/login/?next=/", null),
                new Row("redirect elsewhere is not followed blindly", answer(302, null, "/dashboard", HTML, ""),
                    Kind.UNKNOWN, "/dashboard", null),
                new Row("a password field", answer(200, null, null, HTML,
                    "<form><input name=\"u\"><input type='password' name=\"p\"></form>"), Kind.SIGN_IN_PAGE, null, null),
                new Row("a page offering its own sign-in link", answer(200, null, null, HTML,
                    realContent.replace("</body>", "<a href=\"/login\">Sign in</a></body>")),
                    Kind.SIGN_IN_PAGE, null, null),
                new Row("a real page with no sign-in", answer(200, null, null, HTML, realContent), Kind.NONE, null, null),
                new Row("an app shell that draws itself in script",
                    answer(200, null, null, HTML, "<html><body><app-root></app-root><script src=\"main.js\"></script></body></html>"),
                    Kind.UNKNOWN, null, null),
                new Row("not a web page", answer(200, null, null, "application/json", "{\"ok\":true}"),
                    Kind.UNKNOWN, null, null),
                new Row("forbidden", answer(403, null, null, HTML, realContent), Kind.UNKNOWN, null, null),
                new Row("server error", answer(502, null, null, HTML, realContent), Kind.UNKNOWN, null, null),
                new Row("unreachable", Optional.empty(), Kind.UNKNOWN, null, null),
                // OpenSprinkler's root page says whether it enforces its password.
                new Row("OpenSprinkler enforcing its password", answer(200, null, null, HTML,
                    "<script>var ver=219,ipas=0,dev=0;</script>"), Kind.SIGN_IN_PAGE, null, "OpenSprinkler"),
                new Row("OpenSprinkler ignoring its password", answer(200, null, null, HTML,
                    "<script>var ver=219,ipas=1,dev=0;</script>"), Kind.NONE, null, "OpenSprinkler"))) {
            OwnSignIn signIn = OwnSignIn.classify(row.answer(), NOW);

            assertThat(signIn.kind()).as(row.why()).isEqualTo(row.kind());
            assertThat(signIn.detail()).as(row.why()).isEqualTo(row.detail());
            assertThat(signIn.app()).as(row.why()).isEqualTo(row.app());
            assertThat(signIn.observedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void followOnce_onlyTakesASameOriginRedirectThatIsNotAlreadyASignIn() {
        String from = "http://192.168.1.40:8080/";
        record Row(String location, String expected) {}
        for (Row row : List.of(
                new Row("/admin/", "http://192.168.1.40:8080/admin/"),
                new Row("http://192.168.1.40:8080/admin/", "http://192.168.1.40:8080/admin/"),
                new Row("https://elsewhere.example.com/", null),
                new Row("/login", null))) {
            assertThat(OwnSignIn.followOnce(from, answer(302, null, row.location(), HTML, "")).orElse(null))
                .as(row.location()).isEqualTo(row.expected());
        }
        assertThat(OwnSignIn.followOnce(from, answer(200, null, null, HTML, ""))).isEmpty();
    }

    @Test
    void isDue_whenNeverLookedAt_orOnceTheLastLookIsTenMinutesOld() {
        OwnSignIn signIn = OwnSignIn.classify(Optional.empty(), NOW);

        assertThat(OwnSignIn.isDue(null, NOW)).isTrue();
        assertThat(OwnSignIn.isDue(signIn, NOW.plus(Duration.ofMinutes(9)))).isFalse();
        assertThat(OwnSignIn.isDue(signIn, NOW.plus(Duration.ofMinutes(10)))).isTrue();
    }

    @Test
    void advice_isSaidOnlyWhereThereIsSomethingToDo() {
        OwnSignIn basic = new OwnSignIn(Kind.BASIC, "openHAB", null, NOW);
        OwnSignIn page = new OwnSignIn(Kind.SIGN_IN_PAGE, null, null, NOW);
        OwnSignIn openSprinkler = new OwnSignIn(Kind.SIGN_IN_PAGE, null, OwnSignIn.OPENSPRINKLER, NOW);
        OwnSignIn bearer = new OwnSignIn(Kind.OTHER_CHALLENGE, "Bearer", null, NOW);
        record Row(OwnSignIn signIn, AuthMode mode, boolean credentialSet, Advice expected) {}
        for (Row row : List.of(
                new Row(basic, AuthMode.SOCIAL, false, Advice.SET_SERVICE_CREDENTIAL),
                new Row(basic, AuthMode.NONE, false, Advice.SWITCH_TO_SOCIAL),
                new Row(basic, AuthMode.SOCIAL, true, null),
                new Row(openSprinkler, AuthMode.SOCIAL, false, Advice.IGNORE_OPENSPRINKLER_PASSWORD),
                // Public, its password is the only gate: never suggest dropping it.
                new Row(openSprinkler, AuthMode.NONE, false, null),
                new Row(page, AuthMode.SOCIAL, false, Advice.OWN_SIGN_IN_PAGE),
                new Row(bearer, AuthMode.SOCIAL, false, Advice.CANNOT_SIGN_IN_FOR_THEM),
                new Row(new OwnSignIn(Kind.NONE, null, null, NOW), AuthMode.SOCIAL, false, null),
                new Row(new OwnSignIn(Kind.UNKNOWN, null, null, NOW), AuthMode.NONE, false, null))) {
            assertThat(row.signIn().advice(row.mode(), row.credentialSet()).orElse(null))
                .as("%s %s %s", row.signIn(), row.mode(), row.credentialSet()).isEqualTo(row.expected());
        }
    }
}
