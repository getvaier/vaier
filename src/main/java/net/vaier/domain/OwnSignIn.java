package net.vaier.domain;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What sign-in a published service asks for by itself, as its backend answered Vaier's look. Unknown is
 * never read as none: only a real page with nothing sign-in-like on it counts as no sign-in of its own.
 *
 * @param detail the basic-auth realm, the challenge scheme, or the redirect target, when there is one
 * @param app    a recognised application whose answer said more than the page alone ({@code OpenSprinkler})
 */
public record OwnSignIn(Kind kind, String detail, String app, Instant observedAt) {

    public enum Kind {
        /** HTTP basic auth: a service credential can answer it. */
        BASIC,
        /** Another HTTP challenge (Bearer, Digest, …) Vaier cannot answer. */
        OTHER_CHALLENGE,
        /** Its own sign-in page, which people use after Vaier's. */
        SIGN_IN_PAGE,
        /** A page anyone can read, with no sign-in of its own. */
        NONE,
        /** Vaier could not tell. */
        UNKNOWN
    }

    /** What the operator can do about it, when there is anything. */
    public enum Advice {
        /** Basic auth, and the service carries no service credential: enter one. */
        SET_SERVICE_CREDENTIAL,
        /** Basic auth on a public route: a service credential needs Vaier's sign-in in front first. */
        SWITCH_TO_SOCIAL,
        /** OpenSprinkler still asks for its password behind Vaier's sign-in. */
        IGNORE_OPENSPRINKLER_PASSWORD,
        /** People sign in to it themselves. */
        OWN_SIGN_IN_PAGE,
        /** A challenge Vaier cannot answer; people sign in to it themselves. */
        CANNOT_SIGN_IN_FOR_THEM
    }

    public static final String OPENSPRINKLER = "OpenSprinkler";
    private static final Duration LOOK_AGAIN_AFTER = Duration.ofMinutes(10);
    /** Less readable text than this, and the page is a shell a script fills in — it cannot say what it holds. */
    private static final int MIN_VISIBLE_TEXT = 40;

    private static final Pattern REALM = Pattern.compile("realm\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGN_IN_PATH = Pattern.compile(
        "log[-_]?in|sign[-_]?in|/auth\\b|/sso\\b|oauth|authorize", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_FIELD = Pattern.compile(
        "<input[^>]*type\\s*=\\s*[\"']?password", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGN_IN_WORDS = Pattern.compile(
        "log\\s?in|sign\\s?in|password|passwort|anmelden", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENSPRINKLER_PASSWORD = Pattern.compile("\\bipas=([01])\\b");
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
        "<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // A quoted attribute may hold a `>` (Portainer's ng-class does); the tag ends at the first one outside quotes.
    private static final Pattern TAG = Pattern.compile("<(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");

    public static OwnSignIn classify(Optional<ServiceProbeAnswer> probed, Instant at) {
        if (probed.isEmpty()) {
            return unknown(null, at);
        }
        ServiceProbeAnswer answer = probed.get();
        int status = answer.status();
        if (status == 401) {
            return challenge(answer.wwwAuthenticate(), at);
        }
        if (status >= 300 && status < 400) {
            String location = answer.location();
            return location != null && SIGN_IN_PATH.matcher(location).find()
                ? new OwnSignIn(Kind.SIGN_IN_PAGE, location, null, at)
                : unknown(location, at);
        }
        if (status >= 200 && status < 300) {
            return page(answer, at);
        }
        return unknown(null, at);
    }

    /**
     * The one redirect worth following: back to the same backend, and not already to a sign-in. Anything else
     * is classified where it stands rather than chased.
     */
    public static Optional<String> followOnce(String fromUrl, Optional<ServiceProbeAnswer> probed) {
        if (probed.isEmpty() || probed.get().status() < 300 || probed.get().status() >= 400) {
            return Optional.empty();
        }
        String location = probed.get().location();
        if (location == null || SIGN_IN_PATH.matcher(location).find()) {
            return Optional.empty();
        }
        try {
            URI from = URI.create(fromUrl);
            URI to = from.resolve(location);
            boolean sameOrigin = Objects.equals(from.getScheme(), to.getScheme())
                && Objects.equals(from.getHost(), to.getHost()) && from.getPort() == to.getPort();
            return sameOrigin ? Optional.of(to.toString()) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether a route last seen as {@code last} (null: never) should be looked at again. */
    public static boolean isDue(OwnSignIn last, Instant now) {
        return last == null || !now.isBefore(last.observedAt.plus(LOOK_AGAIN_AFTER));
    }

    /** What to tell the operator, given how the route is gated and whether it carries a service credential. */
    public Optional<Advice> advice(AuthMode mode, boolean serviceCredentialSet) {
        boolean social = mode.isSocial();
        return switch (kind) {
            case BASIC -> serviceCredentialSet ? Optional.empty()
                : Optional.of(social ? Advice.SET_SERVICE_CREDENTIAL : Advice.SWITCH_TO_SOCIAL);
            case SIGN_IN_PAGE -> OPENSPRINKLER.equals(app)
                ? (social ? Optional.of(Advice.IGNORE_OPENSPRINKLER_PASSWORD) : Optional.empty())
                : Optional.of(Advice.OWN_SIGN_IN_PAGE);
            case OTHER_CHALLENGE -> Optional.of(Advice.CANNOT_SIGN_IN_FOR_THEM);
            case NONE, UNKNOWN -> Optional.empty();
        };
    }

    /** Whether this look says something the last one did not — worth telling a pane that is open. */
    public boolean isNewsAfter(OwnSignIn previous) {
        if (previous == null) {
            return kind != Kind.UNKNOWN;
        }
        return kind != previous.kind || !Objects.equals(detail, previous.detail)
            || !Objects.equals(app, previous.app);
    }

    private static OwnSignIn challenge(String wwwAuthenticate, Instant at) {
        if (wwwAuthenticate == null || wwwAuthenticate.isBlank()) {
            return unknown(null, at);
        }
        String scheme = wwwAuthenticate.trim().split("\\s+", 2)[0];
        if (scheme.toLowerCase(Locale.ROOT).equals("basic")) {
            Matcher realm = REALM.matcher(wwwAuthenticate);
            return new OwnSignIn(Kind.BASIC, realm.find() ? realm.group(1) : null, null, at);
        }
        return new OwnSignIn(Kind.OTHER_CHALLENGE, scheme, null, at);
    }

    private static OwnSignIn page(ServiceProbeAnswer answer, Instant at) {
        String body = answer.body() == null ? "" : answer.body();
        Matcher openSprinkler = OPENSPRINKLER_PASSWORD.matcher(body);
        if (openSprinkler.find()) {
            Kind kind = openSprinkler.group(1).equals("0") ? Kind.SIGN_IN_PAGE : Kind.NONE;
            return new OwnSignIn(kind, null, OPENSPRINKLER, at);
        }
        String contentType = answer.contentType() == null ? "" : answer.contentType().toLowerCase(Locale.ROOT);
        if (!contentType.contains("html")) {
            return unknown(null, at);
        }
        if (PASSWORD_FIELD.matcher(body).find() || SIGN_IN_WORDS.matcher(body).find()) {
            return new OwnSignIn(Kind.SIGN_IN_PAGE, null, null, at);
        }
        String visible = TAG.matcher(SCRIPT_OR_STYLE.matcher(body).replaceAll(" ")).replaceAll(" ")
            .replaceAll("\\s+", " ").trim();
        return visible.length() < MIN_VISIBLE_TEXT ? unknown(null, at) : new OwnSignIn(Kind.NONE, null, null, at);
    }

    private static OwnSignIn unknown(String detail, Instant at) {
        return new OwnSignIn(Kind.UNKNOWN, detail, null, at);
    }
}
