package net.vaier.domain;

import lombok.ToString;
import lombok.Value;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A username and password Vaier hands a published service as HTTP basic auth, for someone it has let in.
 * Both ride in a request header, so a value that could split the header or the {@code user:password} pair
 * is refused here.
 */
@Value
public class ServiceCredential {

    private static final int MAX_USERNAME = 256;
    private static final int MAX_PASSWORD = 1024;

    String username;
    @ToString.Exclude
    String password;

    public ServiceCredential(String username, String password) {
        if (username == null || username.isBlank() || username.length() > MAX_USERNAME
                || username.contains(":") || hasControlCharacter(username)) {
            throw new IllegalArgumentException("The username must be 1–" + MAX_USERNAME
                + " characters, with no colon and no line breaks.");
        }
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD
                || hasControlCharacter(password)) {
            throw new IllegalArgumentException("The password must be 1–" + MAX_PASSWORD
                + " characters, with no line breaks.");
        }
        this.username = username.trim();
        this.password = password;
    }

    /** The {@code Authorization} header value the service receives. */
    public String authorizationHeader() {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
