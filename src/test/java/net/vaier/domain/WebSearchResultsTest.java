package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a search hands the model (#360). The decisions are how many results are worth paying for, how they
 * read, and — the one that matters — the difference between "the internet has nothing on this" and "the
 * search would not answer just now". Those are not the same answer, and a model told the first when the
 * second is true will go and invent something.
 */
class WebSearchResultsTest {

    private static WebSearchResult result(int n) {
        return new WebSearchResult("Title " + n, "https://example.com/" + n, "Snippet " + n);
    }

    @Test
    void itReadsAsNumberedLinesWithATitleAnAddressAndASnippet() {
        WebSearchResults found = WebSearchResults.found("allowedips",
            List.of(new WebSearchResult("WireGuard quick start", "https://www.wireguard.com/quickstart/",
                "AllowedIPs is a routing table.")));

        assertThat(found.forModel()).isEqualTo("""
            1. WireGuard quick start
               https://www.wireguard.com/quickstart/
               AllowedIPs is a routing table.""");
    }

    @Test
    void everyResultIsNumberedInTheOrderTheSearchGaveThem() {
        WebSearchResults found = WebSearchResults.found("x", List.of(result(1), result(2), result(3)));

        assertThat(found.forModel()).contains("1. Title 1").contains("2. Title 2").contains("3. Title 3");
        assertThat(found.forModel().indexOf("1. Title 1")).isLessThan(found.forModel().indexOf("2. Title 2"));
    }

    /** A result with no snippet is still an address worth reading; it simply says less. */
    @Test
    void aResultWithNoSnippetIsStillOffered() {
        WebSearchResults found = WebSearchResults.found("x",
            List.of(new WebSearchResult("Just a title", "https://example.com/a", "")));

        assertThat(found.forModel()).isEqualTo("""
            1. Just a title
               https://example.com/a""");
    }

    /** Eight is a page of results. More is a bigger bill for answers the model will not read. */
    @Test
    void itKeepsAtMostEightResults() {
        List<WebSearchResult> many = new ArrayList<>();
        for (int n = 1; n <= 20; n++) {
            many.add(result(n));
        }

        WebSearchResults found = WebSearchResults.found("x", many);

        assertThat(WebSearchResults.MAX_RESULTS).isEqualTo(8);
        assertThat(found.results()).hasSize(8);
        assertThat(found.forModel()).contains("8. Title 8").doesNotContain("9. Title 9");
    }

    @Test
    void aSearchThatFoundNothingSaysSo_namingWhatWasSearchedFor() {
        WebSearchResults nothing = WebSearchResults.found("vaier kumquat protocol", List.of());

        assertThat(nothing.forModel())
            .isEqualTo("The web search found nothing for \"vaier kumquat protocol\".");
    }

    /**
     * Not hypothetical: the first search engine Vaier tried answered this box's datacentre address with a
     * puzzle page and no results at all. "Found nothing" would be a lie the model would then build on.
     */
    @Test
    void aSearchThatWouldNotAnswerSaysThat_ratherThanPretendingNothingExists() {
        WebSearchResults refused = WebSearchResults.notAnswering("allowedips");

        assertThat(refused.forModel()).isEqualTo("The web search is not answering Vaier right now, so this "
            + "question went unsearched. Read a page by its address instead, or try again later.");
        assertThat(refused.results()).isEmpty();
        assertThat(refused.forModel()).doesNotContain("found nothing");
    }
}
