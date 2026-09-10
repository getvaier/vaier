package net.vaier.domain;

/**
 * The tokens one or more Claude API calls used (#360): plain input, output, and the two kinds of cached
 * input, which are priced differently. What Vaier keeps is tokens, never a price — a price is a fact about a
 * day, tokens are a fact about what happened.
 */
public record ModelUsage(String model, long inputTokens, long outputTokens, long cacheWriteTokens,
                         long cacheReadTokens) {

    public static ModelUsage none() {
        return new ModelUsage(null, 0, 0, 0, 0);
    }

    public ModelUsage plus(ModelUsage other) {
        return new ModelUsage(model != null ? model : other.model,
            inputTokens + other.inputTokens, outputTokens + other.outputTokens,
            cacheWriteTokens + other.cacheWriteTokens, cacheReadTokens + other.cacheReadTokens);
    }

    public boolean isNothing() {
        return inputTokens == 0 && outputTokens == 0 && cacheWriteTokens == 0 && cacheReadTokens == 0;
    }
}
