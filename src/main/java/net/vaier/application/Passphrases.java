package net.vaier.application;

import java.security.SecureRandom;

/**
 * Generates strong, backend-owned repository passphrases. Vaier never takes a repository passphrase from a
 * client: when the just-select-and-back-up flow creates a machine's repository, it mints the secret here
 * with {@link SecureRandom} so it is never guessable and never leaves the server in a request body.
 *
 * <p>The alphabet is deliberately alphanumeric (no shell metacharacters): the passphrase is exported into
 * borg's environment and written to a pass-file, so keeping it to {@code [A-Za-z0-9]} avoids any quoting
 * hazard while a 32-character draw over a 62-symbol alphabet still gives ~190 bits of entropy.
 */
public final class Passphrases {

    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passphrases() {
    }

    /** A fresh 32-character alphanumeric passphrase drawn from a cryptographically strong source. */
    public static String strong() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
