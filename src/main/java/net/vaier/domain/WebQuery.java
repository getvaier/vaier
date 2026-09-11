package net.vaier.domain;

import net.vaier.domain.port.ForSearchingTheWeb;

/**
 * What may be sent to a search engine (#360): a few words, trimmed, and not a pasted log. A search box
 * answers a question; a page of text finds nothing and is paid for all the same.
 */
public record WebQuery(String text) {

    public static final int MAX_CHARS = 400;

    public WebQuery {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Say what to search for.");
        }
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("A search is a few words, at most " + MAX_CHARS
                + " characters. Search for less at once.");
        }
    }

    public static WebQuery of(String text) {
        return new WebQuery(text);
    }

    /** The domain asks the port; the service only hands it in. */
    public WebSearchResults search(ForSearchingTheWeb theWeb) {
        return theWeb.search(text);
    }
}
