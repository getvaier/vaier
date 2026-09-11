package net.vaier.adapter.driven;

import net.vaier.adapter.driven.BingRssSearchAdapter.Fetched;
import net.vaier.domain.WebSearchResult;
import net.vaier.domain.WebSearchResults;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Reading Bing's RSS feed (#360), with the wire replaced by a seam and a real feed as the fixture.
 *
 * <p>Two things here are worth more than the rest. The first is {@link #itAsksForExactlyThreeParameters()}: add
 * one more parameter to that URL and Bing answers with ten results for somebody else's question, under a
 * channel title that still echoes ours — a wrong answer that looks exactly like a right one. The second is
 * {@link #itNeverResolvesAnEntityAPageDeclared()}: this is XML off the internet, so the parser must refuse a
 * document type declaration rather than go and fetch what it points at.
 */
class BingRssSearchAdapterTest {

    private final List<URI> asked = new ArrayList<>();

    private BingRssSearchAdapter adapter(int status, String body) {
        return new BingRssSearchAdapter(uri -> {
            asked.add(uri);
            return new Fetched(status, body);
        });
    }

    private BingRssSearchAdapter answering(String fixture) {
        return adapter(200, feed(fixture));
    }

    private static String feed(String fixture) {
        try (InputStream in = BingRssSearchAdapterTest.class.getResourceAsStream("/bing/" + fixture)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("the fixture " + fixture + " is missing", e);
        }
    }

    // --- a feed of results -----------------------------------------------------------------------------

    /** The real feed: one result per item, the title, where it points, and the line Bing wrote about it. */
    @Test
    void itReadsTheTitleTheLinkAndTheDescriptionOfEveryItem() {
        WebSearchResults found = answering("results.rss").search("wireguard handshake timeout");

        assertThat(found.answered()).isTrue();
        assertThat(found.results()).first()
            .extracting(WebSearchResult::title, WebSearchResult::url)
            .containsExactly("WireGuard: fast, modern, secure VPN tunnel", "https://www.wireguard.com/");
        assertThat(found.results().get(0).snippet())
            .startsWith("WireGuard ® is an extremely simple yet fast and modern VPN");
    }

    /** Bing sends ten; the domain keeps a page of eight, in the order they came. */
    @Test
    void itKeepsThePageOfResultsTheDomainAllows_inOrder() {
        WebSearchResults found = answering("results.rss").search("wireguard handshake timeout");

        assertThat(found.results()).hasSize(WebSearchResults.MAX_RESULTS);
        assertThat(found.results()).extracting(WebSearchResult::title, WebSearchResult::url)
            .startsWith(tuple("WireGuard: fast, modern, secure VPN tunnel", "https://www.wireguard.com/"),
                tuple("Installation - WireGuard", "https://download.wireguard.com/"),
                tuple("WireGuard – Wikipedia", "https://de.wikipedia.org/wiki/WireGuard"));
        assertThat(found.results()).extracting(WebSearchResult::title)
            .doesNotContain("Was ist WireGuard? So funktioniert das moderne VPN-Protokoll");
    }

    /** The XML parser decodes the feed's own escaping, so an ampersand arrives as one. */
    @Test
    void aTitleArrivesWithItsEscapingUndone() {
        WebSearchResults found = answering("results.rss").search("wireguard handshake timeout");

        assertThat(found.results()).extracting(WebSearchResult::title)
            .contains("WireGuard erklärt: Schnelles & sicheres VPN nutzen");
    }

    /** An item with nowhere to go is not a result; Marvin cannot read it and cannot cite it. */
    @Test
    void anItemWithNoLinkIsDropped() {
        WebSearchResults found = adapter(200, """
            <?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel>
            <item><title>No link at all</title><description>nowhere</description></item>
            <item><title>Linked</title><link>https://example.com/a</link><description>here</description></item>
            <item><title>Blank link</title><link>   </link><description>nowhere</description></item>
            </channel></rss>""").search("x");

        assertThat(found.results()).extracting(WebSearchResult::title).containsExactly("Linked");
    }

    @Test
    void anItemWithNoDescriptionIsStillAResult() {
        WebSearchResults found = adapter(200, """
            <?xml version="1.0" encoding="utf-8" ?><rss version="2.0"><channel>
            <item><title>Bare</title><link>https://example.com/a</link></item>
            </channel></rss>""").search("x");

        assertThat(found.results()).hasSize(1);
        assertThat(found.results().get(0).snippet()).isEmpty();
    }

    // --- the URL, which is the whole landmine -----------------------------------------------------------

    /**
     * Exactly {@code q}, {@code format} and {@code setlang}, and never a fourth. Verified live from this box:
     * adding {@code cc=US} answered a search for "wireguard handshake timeout" with ten Dutch urinary-tract
     * guidelines, and {@code mkt=en-US} answered it with Google Maps pages — both under
     * {@code <channel><title>Bing: wireguard handshake timeout</title>}, so nothing in the response says it is
     * the wrong answer. A model handed that would state it as fact.
     */
    @Test
    void itAsksForExactlyThreeParameters() {
        answering("results.rss").search("wireguard handshake timeout");

        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).getHost()).isEqualTo("www.bing.com");
        assertThat(asked.get(0).getPath()).isEqualTo("/search");
        assertThat(asked.get(0).getRawQuery())
            .isEqualTo("q=wireguard+handshake+timeout&format=rss&setlang=en");
        assertThat(asked.get(0).getRawQuery()).doesNotContain("cc=").doesNotContain("mkt=");
    }

    @Test
    void itEncodesWhateverWasSearchedFor() {
        answering("results.rss").search("borg prune --keep-daily 7 & 14");

        assertThat(asked.get(0).getRawQuery())
            .isEqualTo("q=borg+prune+--keep-daily+7+%26+14&format=rss&setlang=en");
    }

    // --- the answer that is not an answer ---------------------------------------------------------------

    /** A feed with no items is the search saying no, and that is a real answer. */
    @Test
    void aFeedWithNoItemsIsSaidAsNothingFound() {
        WebSearchResults nothing = answering("empty.rss").search("vaier kumquat protocol");

        assertThat(nothing.answered()).isTrue();
        assertThat(nothing.results()).isEmpty();
        assertThat(nothing.forModel())
            .isEqualTo("The web search found nothing for \"vaier kumquat protocol\".");
    }

    /** A body that is not a feed at all is not an empty internet — it is Vaier being turned away. */
    @Test
    void aBodyThatIsNotAFeedIsSaidAsTheSearchNotAnswering() {
        WebSearchResults refused = adapter(200, "<html><body>Please verify you are human.</body></html>")
            .search("wireguard");

        assertThat(refused.answered()).isFalse();
        assertThat(refused.forModel()).contains("not answering Vaier right now").doesNotContain("found nothing");
    }

    @Test
    void aTruncatedOrEmptyBodyIsSaidAsTheSearchNotAnswering() {
        assertThat(adapter(200, "<rss version=\"2.0\"><channel><item><title>cut off").search("x").answered())
            .isFalse();
        assertThat(adapter(200, "").search("x").answered()).isFalse();
    }

    @Test
    void anyStatusButTwoHundredIsTheSearchNotAnswering() {
        assertThat(adapter(403, feed("results.rss")).search("x").answered()).isFalse();
        assertThat(adapter(503, "").search("x").answered()).isFalse();
        assertThat(adapter(429, "").search("x").results()).isEmpty();
    }

    // --- untrusted XML ----------------------------------------------------------------------------------

    /**
     * The feed is a stranger's XML, and the JDK's parser will happily fetch what a declared entity points at.
     * So a document type declaration is refused outright: the search reports that it did not answer, and not
     * one byte of the named file reaches the model.
     */
    @Test
    void itNeverResolvesAnEntityAPageDeclared() {
        WebSearchResults refused = adapter(200, """
            <?xml version="1.0" encoding="utf-8" ?>
            <!DOCTYPE rss [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel>
            <item><title>&leak;</title><link>https://example.com/a</link><description>&leak;</description></item>
            </channel></rss>""").search("x");

        assertThat(refused.answered()).isFalse();
        assertThat(refused.results()).isEmpty();
        assertThat(refused.forModel()).doesNotContain("root:").doesNotContain("/bin/bash");
    }

    /** The same posture for an entity pointed at a URL: nothing is fetched, and nothing is expanded. */
    @Test
    void itNeverFetchesAnEntityPointedAtAnAddress() {
        WebSearchResults refused = adapter(200, """
            <?xml version="1.0" encoding="utf-8" ?>
            <!DOCTYPE rss SYSTEM "http://169.254.169.254/latest/meta-data/">
            <rss version="2.0"><channel>
            <item><title>t</title><link>https://example.com/a</link></item>
            </channel></rss>""").search("x");

        assertThat(refused.answered()).isFalse();
    }

    /** A transport failure's own words can carry a proxy, a port or a certificate; the host is enough. */
    @Test
    void aSearchThatCouldNotBeReachedIsSaidInVaiersOwnWords() {
        BingRssSearchAdapter adapter = new BingRssSearchAdapter(uri -> {
            throw new IOException("connect to 52.29.74.114:443 via proxy failed");
        });

        assertThatThrownBy(() -> adapter.search("x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not reach www.bing.com.")
            .hasMessageNotContaining("52.29.74.114");
    }
}
