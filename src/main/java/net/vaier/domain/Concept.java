package net.vaier.domain;

import java.util.Locale;

/**
 * A single operator-facing glossary entry: a {@code term} the operator meets in the Vaier UI,
 * a plain-language {@code definition}, and a one-line {@code whyYouCare} explaining why it matters.
 *
 * <p>The {@code slug} is a stable, URL-safe identifier derived from the term so each concept can be
 * deep-linked (e.g. {@code concepts.html#lan-cidr}). Deriving it here keeps the slug a property of
 * the term itself rather than something callers have to hand-maintain and keep in sync.
 */
public record Concept(String slug, String term, String definition, String whyYouCare) {

    /**
     * Build a concept, deriving its slug from the term: lower-cased, with every run of
     * non-alphanumeric characters collapsed into a single hyphen and leading/trailing hyphens
     * trimmed (e.g. {@code "Out-of-date config"} → {@code "out-of-date-config"}).
     */
    public static Concept of(String term, String definition, String whyYouCare) {
        return new Concept(slugOf(term), term, definition, whyYouCare);
    }

    private static String slugOf(String term) {
        return term.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }
}
