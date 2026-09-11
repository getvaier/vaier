package net.vaier.adapter.driven;

import net.vaier.adapter.driven.HttpWebPageAdapter.Hop;
import net.vaier.domain.WebAddress;
import net.vaier.domain.WebPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fetching one page off the public internet (#360), with the wire replaced by a seam. What is pinned here is
 * everything the adapter must do <em>before</em> it connects and at <em>every</em> hop: look the name up, ask
 * the domain whether what it resolved to is public, and refuse a body that is not text — because a redirect is
 * the one way a page the operator asked for can quietly become a read of the fleet's own network.
 */
class HttpWebPageAdapterTest {

    private final Map<String, Hop> wire = new LinkedHashMap<>();
    private final Map<String, List<InetAddress>> names = new LinkedHashMap<>();
    private final List<String> fetched = new ArrayList<>();

    /** The adapter under test, with the network replaced by {@link #wire} and {@link #names}. */
    private HttpWebPageAdapter adapter() {
        return new HttpWebPageAdapter(address -> {
            fetched.add(address.value());
            Hop hop = wire.get(address.value());
            if (hop == null) {
                throw new IOException("nothing is listening on " + address.value());
            }
            return hop;
        }, host -> {
            List<InetAddress> resolved = names.get(host);
            if (resolved == null) {
                throw new UnknownHostException(host);
            }
            return resolved;
        });
    }

    private void resolves(String host, String... literals) throws UnknownHostException {
        List<InetAddress> addresses = new ArrayList<>();
        for (String literal : literals) {
            addresses.add(InetAddress.getByName(literal));
        }
        names.put(host, addresses);
    }

    private void answers(String url, String contentType, String body) {
        answers(url, 200, null, contentType, body, StandardCharsets.UTF_8);
    }

    private void answers(String url, int status, String location, String contentType, String body,
                         Charset charset) {
        wire.put(url, new Hop(status, location, contentType,
            new ByteArrayInputStream(body.getBytes(charset))));
    }

    // --- the ordinary case ------------------------------------------------------------------------------

    @Test
    void itReadsAPageAndHandsBackItsTitleAndText() throws UnknownHostException {
        resolves("www.wireguard.com", "93.184.216.34");
        answers("https://www.wireguard.com/quickstart/", "text/html; charset=utf-8",
            "<html><head><title>Quick start</title></head><body><p>AllowedIPs is a routing table.</p>"
                + "<script>tracker()</script></body></html>");

        WebPage page = adapter().read(WebAddress.of("https://www.wireguard.com/quickstart/"));

        assertThat(page.url()).isEqualTo("https://www.wireguard.com/quickstart/");
        assertThat(page.title()).isEqualTo("Quick start");
        assertThat(page.text()).isEqualTo("Quick start AllowedIPs is a routing table.");
        assertThat(page.cut()).isFalse();
    }

    @Test
    void itReadsATextPageAsItWasWritten() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a.json", "application/json", "{\"version\":\"1.1.8\"}");

        assertThat(adapter().read(WebAddress.of("https://example.com/a.json")).text())
            .isEqualTo("{\"version\":\"1.1.8\"}");
    }

    /** A page that says which alphabet it is written in is read in that alphabet. */
    @Test
    void itReadsThePageInTheCharacterSetThePageNamed() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a.txt", 200, null, "text/plain; charset=ISO-8859-1",
            "Apalveien fødeby", StandardCharsets.ISO_8859_1);

        assertThat(adapter().read(WebAddress.of("https://example.com/a.txt")).text())
            .isEqualTo("Apalveien fødeby");
    }

    // --- the name is looked up before anything is connected ---------------------------------------------

    /**
     * The whole point. A name anyone may hand Marvin can resolve into the tunnel, a house LAN or the cloud's
     * metadata address, and a name is the only way to get there once the literals are refused. So the lookup
     * happens first, the domain judges what came back, and the socket is never opened.
     */
    @Test
    void aNameThatResolvesIntoTheFleetIsRefusedBeforeAnythingIsConnected() throws UnknownHostException {
        resolves("inside.example.com", "10.13.13.6");
        answers("https://inside.example.com/", "text/html", "<p>the Docker API</p>");

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://inside.example.com/")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Marvin reads only the public internet; inside.example.com is not on it.");
        assertThat(fetched).isEmpty();
    }

    /** One private answer among many is enough; a name that resolves to both is a name aimed at the fleet. */
    @Test
    void aNameThatResolvesToOnePrivateAddressAmongSeveralIsRefused() throws UnknownHostException {
        resolves("both.example.com", "93.184.216.34", "169.254.169.254");
        answers("https://both.example.com/", "text/html", "<p>hello</p>");

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://both.example.com/")))
            .hasMessageContaining("both.example.com is not on it");
        assertThat(fetched).isEmpty();
    }

    /** A name that resolved to nothing did not work; it is not forbidden, and it is not said as if it were. */
    @Test
    void aNameThatDoesNotResolveIsSaidAsNotLookedUp() {
        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://nothing.example/")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not look up nothing.example, so it did not try to reach it.");
        assertThat(fetched).isEmpty();
    }

    // --- every redirect is a new address, looked up and judged again -------------------------------------

    @Test
    void itFollowsARedirect_andReadsWhereItLanded() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        resolves("www.example.com", "93.184.216.35");
        answers("https://example.com/a", 301, "https://www.example.com/b", null, "", StandardCharsets.UTF_8);
        answers("https://www.example.com/b", "text/html", "<title>Landed</title><p>here</p>");

        WebPage page = adapter().read(WebAddress.of("https://example.com/a"));

        assertThat(page.url()).isEqualTo("https://www.example.com/b");
        assertThat(page.title()).isEqualTo("Landed");
        assertThat(fetched).containsExactly("https://example.com/a", "https://www.example.com/b");
    }

    @Test
    void aRelativeRedirectIsResolvedAgainstWhereItCameFrom() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a/b", 302, "/c", null, "", StandardCharsets.UTF_8);
        answers("https://example.com/c", "text/plain", "landed");

        assertThat(adapter().read(WebAddress.of("https://example.com/a/b")).text()).isEqualTo("landed");
        assertThat(fetched).containsExactly("https://example.com/a/b", "https://example.com/c");
    }

    /**
     * The hop that matters: a public page may answer with a Location pointing at the fleet. The check is
     * taken again on every hop, so the second request is never made.
     */
    @Test
    void aRedirectOntoTheFleetIsRefusedAtThatHop() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        resolves("inside.example.com", "192.168.1.113");
        answers("https://example.com/a", 302, "https://inside.example.com/", null, "", StandardCharsets.UTF_8);
        answers("https://inside.example.com/", "text/html", "<p>the pool controller</p>");

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/a")))
            .hasMessageContaining("inside.example.com is not on it");
        assertThat(fetched).containsExactly("https://example.com/a");
    }

    /** A redirect to the metadata address is the attack this is all for, written as plainly as it comes. */
    @Test
    void aRedirectToTheCloudsMetadataAddressIsRefused() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a", 302, "http://169.254.169.254/latest/meta-data/iam/", null, "",
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/a")))
            .hasMessageContaining("169.254.169.254 is not on it");
        assertThat(fetched).containsExactly("https://example.com/a");
    }

    @Test
    void itGivesUpAfterFiveRedirects() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        for (int hop = 0; hop <= 9; hop++) {
            answers("https://example.com/" + hop, 302, "/" + (hop + 1), null, "", StandardCharsets.UTF_8);
        }

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/0")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That address redirected more than 5 times, so Marvin stopped following it.");
        assertThat(fetched).hasSize(6);
    }

    @Test
    void aRedirectThatSaysNowhereIsNotFollowed() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a", 302, null, null, "", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/a")))
            .hasMessageContaining("redirected somewhere it did not say");
    }

    // --- what comes back -------------------------------------------------------------------------------

    @Test
    void anythingThatIsNotTextIsRefusedByNamingWhatItWas() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/a.png", "image/png", "PNG, not really");

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/a.png")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That address answered with image/png, which is not text Marvin can read.");
    }

    /** Two megabytes is more page than any question needs; the rest is never pulled down the wire. */
    @Test
    void itReadsAtMostTwoMegabytesOfBody() throws IOException {
        resolves("example.com", "93.184.216.34");
        int tooMuch = HttpWebPageAdapter.MAX_BYTES + 4096;
        InputStream endless = new ByteArrayInputStream("x".repeat(tooMuch).getBytes(StandardCharsets.UTF_8));
        wire.put("https://example.com/huge", new Hop(200, null, "text/plain", endless));

        WebPage page = adapter().read(WebAddress.of("https://example.com/huge"));

        assertThat(HttpWebPageAdapter.MAX_BYTES).isEqualTo(2 * 1024 * 1024);
        assertThat(page.cut()).isTrue();
        assertThat(page.text()).hasSize(WebPage.MAX_CHARS);
        assertThat(endless.available()).isEqualTo(4096);
    }

    @Test
    void aPageThatIsNotThereIsSaidAsTheSiteSaidIt() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");
        answers("https://example.com/gone", 404, null, "text/html", "<p>not found</p>",
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/gone")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("example.com answered 404 for that page, so there was nothing to read.");
    }

    /**
     * A transport failure's own message carries addresses, ports and sometimes a certificate chain, and it
     * goes to the model. So it is said in Vaier's words, and the host is all of it that survives.
     */
    @Test
    void aSiteThatCouldNotBeReachedIsSaidInVaiersOwnWords() throws UnknownHostException {
        resolves("example.com", "93.184.216.34");

        assertThatThrownBy(() -> adapter().read(WebAddress.of("https://example.com/unanswered")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Vaier could not reach example.com.")
            .hasMessageNotContaining("nothing is listening");
    }
}
