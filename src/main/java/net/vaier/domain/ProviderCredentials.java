package net.vaier.domain;

import java.util.regex.Pattern;

/**
 * The client id and client secret an identity provider issued for Vaier's app. Both end up inside a root
 * shell's heredocs and Dex's YAML, so only a strict charset is accepted — enough for every id and secret
 * Google and GitHub issue, and nothing that can quote, expand or break a line.
 */
public record ProviderCredentials(String clientId, String clientSecret) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,256}");

    public ProviderCredentials {
        clientId = checked(clientId, "client id");
        clientSecret = checked(clientSecret, "client secret");
    }

    private static String checked(String value, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (!SAFE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("The " + name + " must be 1–256 letters, digits, dots, dashes "
                + "or underscores — paste it exactly as the provider shows it.");
        }
        return trimmed;
    }
}
