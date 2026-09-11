package net.vaier.domain;

/**
 * One thing a search found (#360): what the page calls itself, where it is, and the line the search engine
 * wrote about it. The snippet is a reason to read the page, never the answer — which is what
 * {@code ChatPrompt} tells the model in so many words.
 */
public record WebSearchResult(String title, String url, String snippet) {}
