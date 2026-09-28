package net.vaier.domain;

/**
 * The <b>Action wording</b>: what a <b>Chat action</b> says to the operator, in two parts. The headline is
 * everyday words anyone can read; the details under it are the exact facts an expert checks — the code, the
 * address, the method and path. The details are never dropped, since they are what the yes really covers.
 */
public record ActionWording(String headline, String details) {

    /** Enough for one plain sentence; anything longer is not a headline. */
    public static final int MAX_HEADLINE_CHARS = 120;

    /** A headline the model wrote, judged before it is shown. */
    public static ActionWording written(String headline, String details) {
        String said = headline == null ? "" : headline.strip();
        if (said.isEmpty() || said.length() > MAX_HEADLINE_CHARS || said.contains("\n")) {
            throw new IllegalArgumentException("Give the headline: one sentence of at most " + MAX_HEADLINE_CHARS
                + " characters, in everyday words, saying what this really does.");
        }
        return new ActionWording(said, details);
    }

    /** Both parts as one line, for the model and for the conversation Vaier keeps. */
    public String sentence() {
        return details == null ? headline : headline + " " + details;
    }
}
