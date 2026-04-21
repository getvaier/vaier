package net.vaier.domain;

import lombok.Value;

import java.util.List;
import java.util.regex.Pattern;

@Value
public class User {

    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    String name;
    String displayname;
    String email;
    List<String> groups;

    public static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    public static void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("email is not a valid format");
        }
    }

    public static void validateDisplayname(String displayname) {
        if (displayname == null || displayname.isBlank()) {
            throw new IllegalArgumentException("displayname must not be blank");
        }
    }
}
