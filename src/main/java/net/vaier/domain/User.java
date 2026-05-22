package net.vaier.domain;

import lombok.Value;

import java.util.List;

@Value
public class User {

    public static final int MIN_PASSWORD_LENGTH = 8;

    /** The group whose members are Vaier administrators. */
    public static final String ADMINS_GROUP = "admins";

    String name;
    String displayname;
    String email;
    List<String> groups;

    /** Whether this user belongs to {@code group}. False when the user has no groups. */
    public boolean isInGroup(String group) {
        return groups != null && groups.contains(group);
    }

    /** Whether this user is a Vaier administrator — a member of {@link #ADMINS_GROUP}. */
    public boolean isAdmin() {
        return isInGroup(ADMINS_GROUP);
    }

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
        if (!EmailFormat.isValid(email)) {
            throw new IllegalArgumentException("email is not a valid format");
        }
    }

    public static void validateDisplayname(String displayname) {
        if (displayname == null || displayname.isBlank()) {
            throw new IllegalArgumentException("displayname must not be blank");
        }
    }

    public static void validateGroupName(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("group name must not be blank");
        }
    }
}
