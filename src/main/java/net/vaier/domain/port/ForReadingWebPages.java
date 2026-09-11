package net.vaier.domain.port;

import net.vaier.domain.WebAddress;
import net.vaier.domain.WebPage;

/**
 * Driven port for fetching one page off the public internet (#360), the reading half of the <b>web read</b>.
 *
 * <p>Deliberately plain: an address the domain has already judged, and the page's text back. No client, no
 * response type and no header crosses this boundary — the one adapter behind it owns redirects, content
 * types, timeouts and how much of a body is worth reading, so what Vaier fetches with can change without the
 * domain hearing about it.
 *
 * <p>The adapter must resolve the address and ask {@link WebAddress#requirePublic} before it connects, and
 * again at every redirect. Whether an address is on the public internet is never the adapter's decision.
 */
public interface ForReadingWebPages {

    /**
     * Fetch the page and read back its text.
     *
     * @param address where to read, already judged to be a public address.
     * @return the page's title and text, cut if it was long — see {@code WebPage}.
     * @throws IllegalArgumentException when the page could not be read — a redirect off the public internet,
     *                                  a content type that is not text, a site that did not answer — carrying
     *                                  a sentence the operator can read and never anything but the host.
     */
    WebPage read(WebAddress address);
}
