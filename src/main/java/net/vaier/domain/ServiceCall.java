package net.vaier.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A <b>Service call</b>: one request Marvin makes to a published service's own API. A <b>free read</b> is a
 * GET on a path Vaier knows changes nothing, and needs no yes; every other call, a GET included, is only
 * ever proposed. The path is judged here so that it can only name something inside the service, never
 * another host.
 */
public record ServiceCall(String method, String path, String body) {

    public static final int MAX_PATH_CHARS = 2_000;
    /** More than any API command needs; a bigger body is not something to approve from one sentence. */
    public static final int MAX_BODY_CHARS = 64_000;
    /** How much of the body the operator's sentence shows; the call itself always carries all of it. */
    public static final int SENTENCE_BODY_CHARS = 200;

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /**
     * The free reads: openHAB's REST reads, and OpenSprinkler's status pages. Keyed on the path alone,
     * because nothing Vaier already holds names a service's kind reliably. Other GETs can act -
     * OpenSprinkler's {@code /cm} switches a station on - so they wait for a yes.
     */
    private static final List<Pattern> FREE_READS = List.of(
        Pattern.compile("/rest(/.*)?"),
        Pattern.compile("/j[cospn]"));
    // RFC 3986 path and query characters: no space, no fragment, no backslash, nothing unprintable.
    private static final Pattern PATH_CHARACTERS = Pattern.compile("[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?]+");

    /** A <b>free read</b>; any other GET is refused and sent to call_service. */
    public static ServiceCall read(String path) {
        String inside = requireInside(path);
        int query = inside.indexOf('?');
        String route = query < 0 ? inside : inside.substring(0, query);
        if (FREE_READS.stream().noneMatch(read -> read.matcher(route).matches())) {
            throw new IllegalArgumentException("GET " + inside + " is not on Vaier's list of reads that change "
                + "nothing, so propose it with call_service and the operator says yes first.");
        }
        return new ServiceCall("GET", inside, null);
    }

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

    /** JSON when the body is shaped like it, plain text otherwise, and nothing without a body. */
    public String contentType() {
        if (body == null) {
            return null;
        }
        String shape = body.strip();
        boolean json = (shape.startsWith("{") && shape.endsWith("}")) || (shape.startsWith("[") && shape.endsWith("]"));
        return json ? "application/json" : "text/plain; charset=utf-8";
    }

    /** What the operator says yes to. A long body is cut here and only here. */
    public String sentence(String service) {
        String said = method + " to " + service + " " + path;
        if (body == null) {
            return said + ".";
        }
        String shown = body.length() <= SENTENCE_BODY_CHARS ? body
            : body.substring(0, SENTENCE_BODY_CHARS) + "… (" + body.length() + " characters)";
        return said + " with body \"" + shown + "\".";
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
