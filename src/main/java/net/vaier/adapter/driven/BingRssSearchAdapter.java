package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.WebSearchResult;
import net.vaier.domain.WebSearchResults;
import net.vaier.domain.port.ForSearchingTheWeb;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The searching half of the <b>web read</b> (#360): Bing's RSS feed, read as RSS.
 *
 * <p>No API key, and no HTML either — asking Bing for {@code format=rss} gets a small, stable feed of ten
 * items instead of a page of markup that changes weekly. It also answers this box, which is what it is here
 * for: the HTML search endpoint Vaier tried first serves a datacentre address a CAPTCHA rather than results.
 *
 * <p><b>Exactly three parameters, and never a fourth.</b> {@code q}, {@code format=rss}, {@code setlang=en}.
 * Verified live from this box: adding {@code cc=US} answered a search for "wireguard handshake timeout" with
 * ten Dutch urinary-tract guidelines, and {@code mkt=en-US} answered the same search with Google Maps pages —
 * both under a {@code <channel><title>} that still echoed our own query, so nothing in the response says it is
 * somebody else's answer. That is far worse than a refusal: it is a wrong answer wearing a right one's label,
 * and a model handed it will state it as fact. {@code BingRssSearchAdapterTest} pins the query string
 * character for character.
 *
 * <p>{@code setlang=en} buys English chrome, not an English result set — from a German address a third of the
 * items come back in German. That is Bing geolocating the caller, it is not worth a parameter to fix, and the
 * one parameter that would try is the landmine above.
 *
 * <p>The feed is a stranger's XML, so the parser is shut down to the bone: no document type declaration, no
 * external entities, no XInclude. An RSS feed needs none of them, and an attacker who gets one gets a read of
 * the file system or a request to the cloud's metadata address.
 */
@Component
@Slf4j
public class BingRssSearchAdapter implements ForSearchingTheWeb {

    private static final String HOST = "www.bing.com";

    /** Said plainly, and Bing answers it as readily as a browser's — so there is no reason to pretend. */
    private static final String USER_AGENT = "Vaier (Marvin)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** One request over the wire, and the seam a test replaces the network at. */
    interface Transport {
        Fetched get(URI uri) throws IOException, InterruptedException;
    }

    /** What the feed answered: the status, and the body. */
    record Fetched(int status, String body) {}

    private final Transport transport;

    public BingRssSearchAdapter() {
        this(overTheWire());
    }

    BingRssSearchAdapter(Transport transport) {
        this.transport = transport;
    }

    @Override
    public WebSearchResults search(String query) {
        Fetched answer = fetch(query);
        if (answer.status() != 200) {
            log.info("The web search answered {} rather than a feed of results", answer.status());
            return WebSearchResults.notAnswering(query);
        }
        List<WebSearchResult> results = read(answer.body());
        if (results == null) {
            return WebSearchResults.notAnswering(query);
        }
        return WebSearchResults.found(query, results);
    }

    // --- reading the feed -------------------------------------------------------------------------------

    /**
     * One result per {@code <item>} that says where it points; an item with no link is not a result, since
     * Marvin can neither read it nor cite it. {@code null} means the body was not a feed at all — which is a
     * different fact from a feed with nothing in it, and is reported as the search not answering.
     */
    private static List<WebSearchResult> read(String body) {
        NodeList items;
        try {
            Document feed = parse(body);
            // Well-formed is not the same as a feed: "please verify you are human" is valid XHTML with no
            // items in it, and would otherwise read as an internet that has nothing on the subject.
            if (!"rss".equalsIgnoreCase(feed.getDocumentElement().getTagName())) {
                log.info("The web search answered with a <{}> document rather than a feed",
                    feed.getDocumentElement().getTagName());
                return null;
            }
            items = feed.getElementsByTagName("item");
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            log.info("The web search answered with something that is not a feed: {}", e.toString());
            return null;
        }
        List<WebSearchResult> results = new ArrayList<>();
        for (int n = 0; n < items.getLength(); n++) {
            if (items.item(n) instanceof Element item) {
                String link = text(item, "link");
                if (!link.isEmpty()) {
                    results.add(new WebSearchResult(text(item, "title"), link, text(item, "description")));
                }
            }
        }
        return results;
    }

    private static String text(Element item, String tag) {
        NodeList found = item.getElementsByTagName(tag);
        if (found.getLength() == 0) {
            return "";
        }
        Node first = found.item(0);
        return first.getTextContent() == null ? "" : first.getTextContent().trim();
    }

    /** The parser, shut down to the bone: this is XML written by whoever answered the request. */
    private static Document parse(String body) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Nothing is ever fetched: every one of the features above is off, and this makes it certain.
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("This feed may not reach out to " + systemId);
        });
        return builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    // --- the wire ---------------------------------------------------------------------------------------

    /**
     * {@code q}, {@code format=rss}, {@code setlang=en}. Read the class comment before adding anything to
     * this URL — a fourth parameter is how the answer silently becomes somebody else's.
     */
    private Fetched fetch(String query) {
        URI uri = URI.create("https://" + HOST + "/search?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=rss&setlang=en");
        try {
            return transport.get(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable(e);
        } catch (IOException | RuntimeException e) {
            throw unreachable(e);
        }
    }

    /** A transport failure's own words carry proxies, ports and certificate chains; the host is enough. */
    private IllegalArgumentException unreachable(Exception cause) {
        log.warn("Chat could not reach the web search: {}", cause.toString());
        return new IllegalArgumentException("Vaier could not reach " + HOST + ".");
    }

    private static Transport overTheWire() {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/rss+xml,application/xml,text/xml")
                .header("User-Agent", USER_AGENT)
                // No Accept-Encoding: this client does not decompress what it asks for.
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Fetched(response.statusCode(), response.body());
        };
    }
}
