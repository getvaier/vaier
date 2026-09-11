package net.vaier.domain.port;

import net.vaier.domain.WebSearchResults;

/**
 * Driven port for asking the public internet a question (#360), the searching half of the <b>web read</b>.
 *
 * <p>A few words in, a handful of results out. Which search engine answers, and how its page is shaped, is
 * the one adapter's business; the domain only ever sees titles, addresses and snippets.
 */
public interface ForSearchingTheWeb {

    /**
     * Search, and read back what was found.
     *
     * @param query what to search for, already judged to be a query worth sending.
     * @return what was found, at most {@code WebSearchResults.MAX_RESULTS} of them — and, when the search
     *         would not answer, results that say so rather than an empty list pretending nothing exists.
     */
    WebSearchResults search(String query);
}
