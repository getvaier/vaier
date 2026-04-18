package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.ChangePasswordUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.domain.port.ForPersistingUsers;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class UserService implements AddUserUseCase, DeleteUserUseCase, ChangePasswordUseCase {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final ForPersistingUsers forPersistingUsers;

    public UserService(ForPersistingUsers forPersistingUsers) {
        this.forPersistingUsers = forPersistingUsers;
    }

    @Override
    public void addUser(String username, String password, String email, String displayname) {
        requireNonBlank(username, "username");
        requirePassword(password);
        requireEmail(email);
        forPersistingUsers.addUser(username, password, email, displayname);
    }

    @Override
    public void deleteUser(String username) {
        requireNonBlank(username, "username");
        forPersistingUsers.deleteUser(username);
    }

    @Override
    public void changePassword(String username, String newPassword) {
        requireNonBlank(username, "username");
        requirePassword(newPassword);
        forPersistingUsers.changePassword(username, newPassword);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static void requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("email is not a valid format");
        }
    }
}
