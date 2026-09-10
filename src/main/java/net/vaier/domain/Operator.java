package net.vaier.domain;

import java.util.Locale;

/**
 * Who is asking (#360 slice 3): the signed-in email, as the key a <b>Conversation</b> is kept under. With
 * nobody signed in there is no gate in front of Vaier at all, and one shared conversation is the honest
 * thing rather than a made-up identity.
 */
public record Operator(String key) {

    private static final String NOBODY = "operator";

    public static Operator of(String email) {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return new Operator(key.isEmpty() ? NOBODY : key);
    }

    /** A name safe for any file system, derived from the key and nothing else. */
    public String fileName() {
        return key.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
