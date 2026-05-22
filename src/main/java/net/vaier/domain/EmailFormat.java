package net.vaier.domain;

import java.util.regex.Pattern;

/**
 * The project's single definition of "is this a well-formed email address?". Used wherever
 * an email needs validating — operator emails on {@link User}, the ACME contact on
 * {@link VaierConfig} — so the rule is tightened in one place.
 */
public final class EmailFormat {

    private static final Pattern PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailFormat() {}

    public static boolean isValid(String email) {
        return email != null && PATTERN.matcher(email).matches();
    }
}
