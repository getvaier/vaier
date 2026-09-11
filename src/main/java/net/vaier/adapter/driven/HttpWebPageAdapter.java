package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.WebAddress;
import net.vaier.domain.WebPage;
import net.vaier.domain.port.ForReadingWebPages;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The reading half of the <b>web read</b> (#360): one page off the public internet, over the JDK's own HTTP
 * client, and no new dependency for it.
 *
 * <p>It follows redirects by hand, which is the whole reason it exists in this shape. The client is told
 * {@code Redirect.NEVER} because a client that hops on its own hops without asking, and the one question that
 * must be asked at every hop is whether the next address is on the public internet — a perfectly ordinary
 * public page can answer with a {@code Location} pointing at the tunnel, a house LAN, or the cloud's metadata
 * address. So each hop is: look the name up, let {@link WebAddress#requirePublic} judge what came back, and
 * only then connect.
 *
 * <p>That leaves a race the JDK client cannot close: the name is resolved here and resolved again inside the
 * client when it connects, so a name that answers differently the second time would be followed. Closing it
 * needs connecting to the address rather than the name, which this client does not offer; the race is
 * accepted.
 *
 * <p>Translation only. Which addresses are allowed, which content types are words, and how much of a page is
 * worth reading are all {@link WebAddress}'s and {@link WebPage}'s decisions.
 */
@Component
@Slf4j
public class HttpWebPageAdapter implements ForReadingWebPages {

    /** More page than any question needs; the rest is never pulled down the wire. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    /** Five is what every browser settles on, and a sixth hop is a loop dressed as a journey. */
    private static final int MAX_REDIRECTS = 5;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    /**
     * A browser-ish one, because a good many sites answer a nameless client with a refusal and the operator
     * would read that as Vaier being broken.
     */
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Vaier/1.0";

    /** What one request came back with, and the seam a test replaces the network at. */
    interface Transport {
        Hop get(WebAddress address) throws IOException, InterruptedException;
    }

    /** One hop's answer: the status, where it points next, what it says it is, and the body unread. */
    record Hop(int status, String location, String contentType, InputStream body) {}

    /** Looking a name up, as its own seam — a test must be able to say what a name resolves to. */
    interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private final Transport transport;
    private final Resolver resolver;

    public HttpWebPageAdapter() {
        this(overTheWire(), host -> List.of(InetAddress.getAllByName(host)));
    }

    HttpWebPageAdapter(Transport transport, Resolver resolver) {
        this.transport = transport;
        this.resolver = resolver;
    }

    @Override
    public WebPage read(WebAddress address) {
        WebAddress here = address;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            here.requirePublic(resolve(here));
            Hop answer = fetch(here);
            try {
                if (!REDIRECTS.contains(answer.status())) {
                    return page(here, answer);
                }
                here = here.redirectedTo(answer.location());
            } finally {
                close(answer);
            }
        }
        throw new IllegalArgumentException("That address redirected more than " + MAX_REDIRECTS
            + " times, so Marvin stopped following it.");
    }

    /** The page itself: the status judged, the content type judged, and then at most {@link #MAX_BYTES}. */
    private WebPage page(WebAddress address, Hop answer) {
        if (answer.status() < 200 || answer.status() >= 300) {
            throw new IllegalArgumentException(address.host() + " answered " + answer.status()
                + " for that page, so there was nothing to read.");
        }
        // Asked before the body is read, so an installer is refused rather than downloaded.
        WebPage.requireReadable(answer.contentType());
        return WebPage.of(address.value(), answer.contentType(), body(address, answer));
    }

    private String body(WebAddress address, Hop answer) {
        try {
            return new String(answer.body().readNBytes(MAX_BYTES), charsetOf(answer.contentType()));
        } catch (IOException e) {
            throw unreachable(address, e);
        }
    }

    private List<InetAddress> resolve(WebAddress address) {
        try {
            return resolver.resolve(address.host());
        } catch (UnknownHostException e) {
            // An empty list is how the domain words "not looked up", which is not the same as "private".
            return List.of();
        }
    }

    private Hop fetch(WebAddress address) {
        try {
            return transport.get(address);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(address, e);
        } catch (IOException | RuntimeException e) {
            throw unreachable(address, e);
        }
    }

    /** A transport failure's own words carry addresses, ports and certificate chains; the host is enough. */
    private IllegalArgumentException unreachable(WebAddress address, Exception cause) {
        log.warn("Chat could not read {}: {}", address.host(), cause.toString());
        return new IllegalArgumentException("Vaier could not reach " + address.host() + ".");
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String piece = part.trim();
                if (piece.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(piece.substring("charset=".length()).replace("\"", "").trim());
                    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Transport overTheWire() {
        HttpClient client = HttpClient.newBuilder()
            // Never: a client that hops on its own hops without the public-internet check.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        return address -> {
            URI uri = address.uri();
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                .header("Accept-Language", "en")
                .header("User-Agent", USER_AGENT)
                // No Accept-Encoding: this client does not decompress what it asks for.
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Hop(response.statusCode(),
                response.headers().firstValue("location").orElse(null),
                response.headers().firstValue("content-type").orElse(null),
                response.body());
        };
    }

    /** Every hop's body is closed, read or not — a redirect's body is never read at all. */
    private static void close(Hop answer) {
        if (answer.body() == null) {
            return;
        }
        try {
            answer.body().close();
        } catch (IOException e) {
            log.debug("A web page's body would not close: {}", e.toString());
        }
    }
}
