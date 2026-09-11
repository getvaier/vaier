package net.vaier.domain;

import java.util.List;

/**
 * What a search came back with (#360), shaped for a reader rather than a caller.
 *
 * <p>The decision that matters is {@link #answered}. A search that ran and matched nothing, and a search that
 * would not answer at all, are different facts: the first is the internet saying no, the second is Vaier being
 * turned away or handed something that is not results at all. Told the first when the second is true, a model
 * concludes the thing does not exist and says so with confidence. So both are said in words, and the model is
 * pointed at reading a page by its address instead.
 *
 * <p>This is not hypothetical. The first search engine Vaier tried refused a datacentre address outright, with
 * a puzzle page in place of every result; the one behind it now answers, but a search engine that stops
 * answering is a thing that happens, and it must never read as an empty internet.
 */
public record WebSearchResults(String query, List<WebSearchResult> results, boolean answered) {

    /** A page of results. More is a bigger bill for addresses the model will not open. */
    public static final int MAX_RESULTS = 8;

    public WebSearchResults {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static WebSearchResults found(String query, List<WebSearchResult> results) {
        List<WebSearchResult> some = results == null ? List.of() : results;
        return new WebSearchResults(query, some.subList(0, Math.min(some.size(), MAX_RESULTS)), true);
    }

    /** The search would not answer Vaier at all — a puzzle page, a refusal, a silence. */
    public static WebSearchResults notAnswering(String query) {
        return new WebSearchResults(query, List.of(), false);
    }

    /** What the model reads: numbered lines, one result each, title then address then what it says. */
    public String forModel() {
        if (!answered) {
            return "The web search is not answering Vaier right now, so this question went unsearched. "
                + "Read a page by its address instead, or try again later.";
        }
        if (results.isEmpty()) {
            return "The web search found nothing for \"" + query + "\".";
        }
        StringBuilder lines = new StringBuilder();
        for (int n = 0; n < results.size(); n++) {
            WebSearchResult result = results.get(n);
            if (n > 0) {
                lines.append('\n');
            }
            lines.append(n + 1).append(". ").append(result.title()).append('\n');
            lines.append("   ").append(result.url());
            if (result.snippet() != null && !result.snippet().isBlank()) {
                lines.append('\n').append("   ").append(result.snippet());
            }
        }
        return lines.toString();
    }
}
