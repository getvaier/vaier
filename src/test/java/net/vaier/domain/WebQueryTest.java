package net.vaier.domain;

import net.vaier.domain.port.ForSearchingTheWeb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What may be sent to a search engine (#360): a few words, and not a page of them. */
class WebQueryTest {

    @Test
    void itTakesAFewWords_trimmed() {
        assertThat(WebQuery.of("  wireguard allowedips  ").text()).isEqualTo("wireguard allowedips");
    }

    @Test
    void itRefusesNothingAtAll() {
        assertThatThrownBy(() -> WebQuery.of("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say what to search for.");
        assertThatThrownBy(() -> WebQuery.of(null)).hasMessage("Say what to search for.");
    }

    /** A search box takes a question, not a pasted log; a long one finds nothing and costs the same. */
    @Test
    void itRefusesAQueryLongerThanFourHundredCharacters() {
        assertThatThrownBy(() -> WebQuery.of("x".repeat(401)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A search is a few words, at most 400 characters. Search for less at once.");
        assertThat(WebQuery.of("x".repeat(400)).text()).hasSize(400);
        assertThat(WebQuery.MAX_CHARS).isEqualTo(400);
    }

    /** The domain asks the port; the service only hands it in. */
    @Test
    void search_asksThePortWithTheWordsItJudged() {
        List<String> asked = new ArrayList<>();
        ForSearchingTheWeb port = query -> {
            asked.add(query);
            return WebSearchResults.found(query, List.of());
        };

        WebSearchResults results = WebQuery.of("  borg prune  ").search(port);

        assertThat(asked).containsExactly("borg prune");
        assertThat(results.query()).isEqualTo("borg prune");
    }
}
