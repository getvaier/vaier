package net.vaier.domain;

import java.util.Map;

/**
 * Anthropic's list price per million tokens for the models Vaier may pin (#360). Cache writes cost 1.25×
 * the input price (the five-minute cache Vaier uses) and cache reads 0.1×. A model without a row here is
 * refused, never priced at nothing: a figure that quietly left something out would be worse than none.
 */
public record ClaudePrice(String model, double inputPerMillion, double outputPerMillion) {

    private static final Map<String, ClaudePrice> LIST = Map.of(
        "claude-opus-5", new ClaudePrice("claude-opus-5", 5.0, 25.0),
        "claude-opus-4-8", new ClaudePrice("claude-opus-4-8", 5.0, 25.0),
        "claude-opus-4-7", new ClaudePrice("claude-opus-4-7", 5.0, 25.0),
        "claude-sonnet-5", new ClaudePrice("claude-sonnet-5", 2.0, 10.0),
        "claude-sonnet-4-6", new ClaudePrice("claude-sonnet-4-6", 3.0, 15.0),
        "claude-haiku-4-5", new ClaudePrice("claude-haiku-4-5", 1.0, 5.0));

    public static ClaudePrice forModel(String model) {
        ClaudePrice price = model == null ? null : LIST.get(model);
        if (price == null) {
            throw new IllegalArgumentException("Vaier has no price for " + model + ".");
        }
        return price;
    }

    public double cacheWritePerMillion() {
        return inputPerMillion * 1.25;
    }

    public double cacheReadPerMillion() {
        return inputPerMillion * 0.1;
    }

    public double costUsd(ModelUsage usage) {
        return (usage.inputTokens() * inputPerMillion
            + usage.outputTokens() * outputPerMillion
            + usage.cacheWriteTokens() * cacheWritePerMillion()
            + usage.cacheReadTokens() * cacheReadPerMillion()) / 1_000_000.0;
    }
}
