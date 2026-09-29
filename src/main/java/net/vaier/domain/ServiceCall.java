package net.vaier.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A <b>Service call</b>: one request Marvin makes to a published service's own API. The path is judged here
 * so that it can only name something inside the service, never another host. Whether a GET is a
 * <b>free read</b> is the service's own list's decision ({@link FreeReads}).
 */
public record ServiceCall(String method, String path, String body) {

    public static final int MAX_PATH_CHARS = 2_000;
    /** More than any API command needs; a bigger body is not something to approve from one sentence. */
    public static final int MAX_BODY_CHARS = 64_000;
    /** How much of the body the card's details show; the call itself always carries all of it. */
    public static final int DETAILS_BODY_CHARS = 200;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    // RFC 3986 path and query characters: no space, no fragment, no backslash, nothing unprintable.
    private static final Pattern PATH_CHARACTERS = Pattern.compile("[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?]+");

    /** A call that waits for the operator's yes. */
    public static ServiceCall proposed(String method, String path, String body) {
        String verb = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(verb)) {
            throw new IllegalArgumentException("A service call is GET, POST, PUT, PATCH or DELETE.");
        }
        String kept = body == null || body.isBlank() ? null : body;
        if (kept != null && verb.equals("GET")) {
            throw new IllegalArgumentException("A GET carries no body.");
        }
        if (kept != null && kept.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("That body is longer than " + MAX_BODY_CHARS
                + " characters, which is more than one yes should cover.");
        }
        return new ServiceCall(verb, requireInside(path), kept);
    }

    /** The path without its query: what <b>Always allow</b> saves, and what a free read is matched on. */
    public String route() {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /**
     * What Always allow saves: the folder the call reads in, so one yes covers its siblings. A call right under
     * the root keeps its own path instead, because the root's folder would be the whole service.
     */
    public String allowance() {
        String route = route();
        String trimmed = route.length() > 1 && route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
        String parent = trimmed.substring(0, trimmed.lastIndexOf('/') + 1);
        return parent.equals("/") ? route : parent;
    }

    /** Only a read may be allowed forever; a write waits for a yes every time. */
    public boolean mayBeAlwaysAllowed() {
        return method.equals("GET");
    }

    /** The service's own answer, and under it the path now read without asking. */
    public ActionWording alwaysAllowed(ActionWording outcome, String service) {
        String saved = "Marvin reads everything under " + allowance() + " on " + service + " without asking from now on.";
        return new ActionWording(outcome.headline(), outcome.details() == null ? saved : outcome.details() + " " + saved);
    }

    /** JSON when the body is shaped like it, plain text otherwise, and nothing without a body. */
    public String contentType() {
        if (body == null) {
            return null;
        }
        String shape = body.strip();
        boolean json = (shape.startsWith("{") && shape.endsWith("}")) || (shape.startsWith("[") && shape.endsWith("]"));
        return json ? "application/json" : "text/plain; charset=utf-8";
    }

    /** The exact call under the card's plain headline: what the yes covers. A long body is cut here and only here. */
    public String details(String service) {
        String said = "Sends " + method + " " + path + " to " + service;
        if (body == null) {
            return said + ".";
        }
        String shown = body.length() <= DETAILS_BODY_CHARS ? body
            : body.substring(0, DETAILS_BODY_CHARS) + "… (" + body.length() + " characters)";
        return said + ", with \"" + shown + "\".";
    }

    private static String requireInside(String said) {
        String path = said == null ? "" : said.trim();
        if (path.isEmpty()) {
            throw new IllegalArgumentException("Say which path on the service, for example /rest/items.");
        }
        if (path.contains("://") || path.startsWith("//")) {
            throw new IllegalArgumentException("A path is inside the service: no scheme and no host.");
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > MAX_PATH_CHARS || !PATH_CHARACTERS.matcher(path).matches()) {
            throw new IllegalArgumentException("That is not a path Vaier will send: at most " + MAX_PATH_CHARS
                + " characters, with no spaces, no # and no backslash.");
        }
        int query = path.indexOf('?');
        String route = (query < 0 ? path : path.substring(0, query)).toLowerCase(Locale.ROOT);
        boolean escapes = route.contains("%2e") || route.contains("%2f") || route.contains("%5c")
            || Arrays.stream(route.split("/")).anyMatch(segment -> segment.equals("..") || segment.equals("."));
        if (escapes) {
            throw new IllegalArgumentException("A path stays inside the service: no . or .. segments.");
        }
        return path;
    }
}
