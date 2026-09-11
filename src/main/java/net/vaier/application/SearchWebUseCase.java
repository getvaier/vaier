package net.vaier.application;

import net.vaier.domain.WebSearchResults;

/**
 * Search the public internet, the searching half of the <b>web read</b> (#360). What may be sent is the
 * domain's decision ({@code WebQuery}); a search that would not answer says so rather than coming back empty.
 *
 * <p>Throws {@code IllegalArgumentException} when the query is refused, worded for the one who asked.
 */
public interface SearchWebUseCase {

    WebSearchResults search(String query);
}
